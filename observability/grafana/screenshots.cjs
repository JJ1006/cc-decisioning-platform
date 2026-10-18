// Visual check of the Grafana dashboards with Playwright (headless Chromium): logs in as admin, opens every provisioned dashboard
// (last hour), waits for the panels, lists panels that show "No data" or an error, writes a full-page screenshot per dashboard, then the
// service graph and one trace waterfall (gateway to notification) from Tempo. Run through scripts/grafana-shots.sh.
//
//   GRAFANA_PASSWORD=... OUT_DIR=docs/images node observability/grafana/screenshots.cjs [dashboard-uid ...]
const { chromium } = require("playwright");
const fs = require("fs");
const path = require("path");

const BASE = process.env.GRAFANA_URL || "https://localhost:3000";
const OUT = process.env.OUT_DIR || "docs/images";
const PASS = process.env.GRAFANA_PASSWORD || "";
const DASHBOARDS = {
  "cc-overview": "platform-overview",
  "cc-kafka": "kafka",
  "cc-jvm": "jvm",
  "cc-api-slo": "api-slo",
  "cc-apisix": "apisix",
  "cc-elk": "elasticsearch-logstash",
  "cc-odm": "odm-latency",
  "cc-business": "business",
};

async function settle(page, ms = 6000) {
  await page.waitForLoadState("networkidle", { timeout: 30000 }).catch(() => {});
  await page.waitForFunction(() => document.querySelectorAll('[aria-label="Panel loading bar"], .panel-loading, [data-testid="data-testid Panel loading"]').length === 0, null, { timeout: 30000, polling: 500 }).catch(() => {});
  await page.waitForTimeout(ms);
}

(async () => {
  const wanted = process.argv.slice(2);
  const uids = Object.keys(DASHBOARDS).filter((u) => wanted.length === 0 || wanted.includes(u));
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1600, height: 1000 } });
  const page = await context.newPage();

  await page.goto(`${BASE}/login`, { waitUntil: "domcontentloaded", timeout: 90000 });
  await page.fill('input[name="user"]', "admin");
  await page.fill('input[name="password"]', PASS);
  await page.click('button[type="submit"]');
  await page.waitForURL((u) => !u.pathname.includes("login"), { timeout: 60000 });

  let problems = 0;
  for (const uid of uids) {
    await page.setViewportSize({ width: 1600, height: 1000 });
    await page.goto(`${BASE}/d/${uid}?orgId=1&from=now-1h&to=now&refresh=`, { waitUntil: "domcontentloaded", timeout: 90000 });
    await settle(page);
    // grow the viewport to the whole dashboard so the screenshot has every panel rendered
    const height = await page.evaluate(() => {
      const grid = document.querySelector(".react-grid-layout");
      return grid ? grid.getBoundingClientRect().height + 220 : document.documentElement.scrollHeight;
    });
    await page.setViewportSize({ width: 1600, height: Math.min(Math.ceil(height), 5000) });
    await settle(page, 8000);

    const report = await page.evaluate(() => {
      const out = [];
      document.querySelectorAll('section[data-testid^="data-testid Panel header"]').forEach((p) => {
        const title = (p.getAttribute("data-testid") || "").replace("data-testid Panel header ", "");
        const text = (p.innerText || "").replace(/\s+/g, " ");
        const err = p.querySelector('[data-testid="data-testid Panel status error"]');
        let status = "ok";
        if (err) status = "ERROR";
        else if (/No data/i.test(text)) status = "NO DATA";
        out.push({ title, status });
      });
      return out;
    });
    const file = path.join(OUT, `grafana-${DASHBOARDS[uid]}.png`);
    await page.screenshot({ path: file, fullPage: true });
    console.log(`\n== ${uid} (${report.length} panels) -> ${file}`);
    for (const r of report) {
      if (r.status !== "ok") problems++;
      console.log(`  ${r.status === "ok" ? "ok      " : "PROBLEM "} ${r.title}${r.status === "ok" ? "" : "  [" + r.status + "]"}`);
    }
  }

  if (wanted.length === 0) {
    // service graph (Tempo, spanmetrics in Prometheus) and one full-chain trace waterfall
    const explore = (query) => {
      const panes = { a: { datasource: "tempo", queries: [{ refId: "A", datasource: { type: "tempo", uid: "tempo" }, ...query }], range: { from: "now-1h", to: "now" } } };
      return `${BASE}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify(panes))}`;
    };
    await page.setViewportSize({ width: 1600, height: 1100 });
    await page.goto(explore({ queryType: "serviceMap" }), { waitUntil: "domcontentloaded", timeout: 90000 });
    await settle(page, 10000);
    await page.screenshot({ path: path.join(OUT, "grafana-service-graph.png") });
    console.log("\n== service graph -> grafana-service-graph.png");

    const now = Math.floor(Date.now() / 1000);
    const q = encodeURIComponent('{ resource.service.name = "apisix" && name = "POST /v1/applications" }');
    const res = await page.request.get(`${BASE}/api/datasources/proxy/uid/tempo/api/search?limit=20&start=${now - 3600}&end=${now}&q=${q}`);
    const traces = (await res.json()).traces || [];
    let chosen = null;
    for (const t of traces) {
      const tr = await (await page.request.get(`${BASE}/api/datasources/proxy/uid/tempo/api/traces/${t.traceID}`)).json();
      const services = new Set((tr.batches || []).map((b) => (b.resource.attributes.find((a) => a.key === "service.name") || { value: {} }).value.stringValue));
      if (services.has("apisix") && services.has("application-api") && services.has("decision-orchestrator") && services.has("notification-svc")) {
        chosen = { id: t.traceID, services: [...services] };
        break;
      }
    }
    if (!chosen) {
      console.log("PROBLEM no full-chain trace (apisix, application-api, decision-orchestrator, notification-svc) found: submit an application through the gateway first");
      problems++;
    } else {
      await page.setViewportSize({ width: 1600, height: 1300 });
      await page.goto(explore({ queryType: "traceql", query: chosen.id }), { waitUntil: "domcontentloaded", timeout: 90000 });
      await settle(page, 10000);
      await page.screenshot({ path: path.join(OUT, "grafana-trace-waterfall.png") });
      console.log(`\n== trace ${chosen.id} (${chosen.services.join(", ")}) -> grafana-trace-waterfall.png`);
    }
  }
  await browser.close();
  console.log(`\npanels with a problem: ${problems}`);
  process.exit(problems > 0 ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(2);
});
