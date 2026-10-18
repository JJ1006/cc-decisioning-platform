// Visual check of the Kibana dashboards with Playwright (headless Chromium): logs in as a Kibana user, opens every dashboard of the
// space for the last 24 hours, waits until the panels have loaded, reports panels that show an error or "No results", and
// writes a full-page screenshot per dashboard. Run through scripts/kibana-shots.sh (it installs Playwright into tmp/ and passes the
// password from the root env file); the browser accepts our private CA's certificate because localhost is the only host it visits.
//
//   KIBANA_USER=analyst KIBANA_PASSWORD=... OUT_DIR=docs/images node observability/kibana/screenshots.cjs [dashboard-id ...]
const { chromium } = require("playwright");
const fs = require("fs");
const path = require("path");

const BASE = process.env.KIBANA_URL || "https://localhost:5601";
const SPACE = process.env.KIBANA_SPACE || "maple-northern";
const USER = process.env.KIBANA_USER || "analyst";
const PASS = process.env.KIBANA_PASSWORD || "";
const OUT = process.env.OUT_DIR || "docs/images";
const PREFIX = process.env.SHOT_PREFIX || "kibana-";
const TIME = process.env.TIME_RANGE || "now-24h";
const DASHBOARDS = {
  "mn-dash-exec": "executive",
  "mn-dash-risk": "risk-fairness",
  "mn-dash-ops": "operations",
  "mn-dash-trace": "trace",
};

(async () => {
  const wanted = process.argv.slice(2);
  const ids = Object.keys(DASHBOARDS).filter((id) => wanted.length === 0 || wanted.includes(id));
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1600, height: 1000 } });
  const page = await context.newPage();

  await page.goto(`${BASE}/login`, { waitUntil: "domcontentloaded" });
  await page.fill('input[name="username"]', USER);
  await page.fill('input[name="password"]', PASS);
  await page.click('button[type="submit"]');
  await page.waitForURL((u) => !u.pathname.includes("login"), { timeout: 60000 });

  let problems = 0;
  for (const id of ids) {
    const url = `${BASE}/s/${SPACE}/app/dashboards#/view/${id}?_g=(refreshInterval:(pause:!t,value:60000),time:(from:${TIME},to:now))`;
    await page.goto(url, { waitUntil: "domcontentloaded" });
    await page.waitForSelector('[data-test-subj="embeddablePanel"], [data-test-subj="dashboardPanel"]', { timeout: 90000 }).catch(() => {});
    // wait until no panel is still loading (spinners) for a stable moment
    await page.waitForFunction(
      () => document.querySelectorAll('.euiLoadingChart, .euiLoadingSpinner, [data-loading="true"], [data-render-complete="false"]').length === 0,
      null,
      { timeout: 90000, polling: 1000 }
    ).catch(() => {});
    await page.waitForTimeout(4000);
    // make every panel render: scroll through the page (panels below the fold render lazily)
    const height = await page.evaluate(() => document.documentElement.scrollHeight);
    for (let y = 0; y < height; y += 600) {
      await page.evaluate((top) => window.scrollTo(0, top), y);
      await page.waitForTimeout(700);
    }
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForFunction(
      () => document.querySelectorAll('.euiLoadingChart, [data-loading="true"], [data-render-complete="false"]').length === 0,
      null,
      { timeout: 60000, polling: 1000 }
    ).catch(() => {});
    await page.waitForTimeout(2500);

    const report = await page.evaluate(() => {
      const out = [];
      document.querySelectorAll('[data-test-subj="embeddablePanel"]').forEach((p) => {
        const title =
          (p.querySelector('[data-test-subj="embeddablePanelTitleInner"], h2, [data-test-subj="dashboardPanelTitle"]') || {}).textContent ||
          "(untitled)";
        const text = p.innerText || "";
        const error = p.querySelector('[data-test-subj="embeddableError"], [data-test-subj="embeddable-lens-failure"]');
        let status = "ok";
        const failure = /Provided column|An error occurred|Could not locate|Error in visualization|Cannot read|Request .* failed|security_exception|index_not_found|Unable to/i;
        const isSearchPanel = /\d+ documents/i.test(text); // document tables show data that may itself mention errors
        if (error || (!isSearchPanel && failure.test(text))) status = "ERROR: " + ((error && error.innerText) || text).replace(/\s+/g, " ").slice(0, 160);
        else if (/no results found/i.test(text)) status = "NO RESULTS";
        else if (text.replace(/\s+/g, "").length < 40 && !/documents/i.test(text) && !/\d/.test(text)) status = "EMPTY";
        out.push({ title: title.trim(), status });
      });
      return out;
    });
    const file = path.join(OUT, `${PREFIX}${DASHBOARDS[id]}.png`);
    await page.screenshot({ path: file, fullPage: true });
    console.log(`\n== ${id} (${report.length} panels) -> ${file}`);
    // panels that are legitimately empty on a healthy system: no dead letters, no alerts fired
    const mayBeEmpty = /^(Panel: )?(Alerts|Alerts fired|Dead letters)/;
    for (const r of report) {
      if (r.status !== "ok" && !(mayBeEmpty.test(r.title) && /EMPTY|NO RESULTS/.test(r.status))) problems++;
      console.log(`  ${r.status === "ok" ? "ok      " : "PROBLEM "} ${r.title}${r.status === "ok" ? "" : "  [" + r.status + "]"}`);
    }
  }
  await browser.close();
  console.log(`\npanels with a problem: ${problems}`);
  process.exit(problems > 0 ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(2);
});
