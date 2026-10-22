// Load profile through the gateway (mutual TLS, the demo-partner certificate): steady 20 requests per second for 5 minutes, then a spike of 100 per second for 1 minute.
// Thresholds (the SLOs): POST /v1/applications p95 < 300 ms, errors < 1%, and end to end (submit -> decided status) p95 < 2 s, measured on a 1 in 20 sample by
// polling GET /v1/applications/{id}/status. Run with evals/k6/run.sh (make load-test): it raises the gateway quota for the run only and restores it.
//
// Environment (all optional): GATEWAY (https://localhost:8443), CERT_DIR (security/pki/out), STEADY_RATE (20), STEADY_DURATION (5m), SPIKE_RATE (100),
// SPIKE_DURATION (1m), SAMPLE_EVERY (20), OUT_DIR (evals/k6/out)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const GATEWAY = __ENV.GATEWAY || 'https://localhost:8443';
const CERT_DIR = __ENV.CERT_DIR || '../../security/pki/out';
const STEADY_RATE = Number(__ENV.STEADY_RATE || 20);
const STEADY_DURATION = __ENV.STEADY_DURATION || '5m';
const SPIKE_RATE = Number(__ENV.SPIKE_RATE || 100);
const SPIKE_DURATION = __ENV.SPIKE_DURATION || '1m';
const SAMPLE_EVERY = Number(__ENV.SAMPLE_EVERY || 20);

const template = JSON.parse(open('../../contracts/examples/application-submitted.example.json')).payload.application;
delete template.applicationId;

const e2e = new Trend('e2e_decision_ms', true);
const decided = new Rate('e2e_decided_within_10s');
const submitted = new Counter('applications_submitted');

export const options = {
  // the gateway certificate is checked against our CA by run.sh before the run (k6 has no option for a private CA); the client certificate is the consumer's
  insecureSkipTLSVerify: true,
  tlsAuth: [{ domains: ['localhost'], cert: open(`${CERT_DIR}/demo-partner/demo-partner.crt`), key: open(`${CERT_DIR}/demo-partner/demo-partner.key`) }],
  scenarios: {
    steady: { executor: 'constant-arrival-rate', rate: STEADY_RATE, timeUnit: '1s', duration: STEADY_DURATION, preAllocatedVUs: 60, maxVUs: 400, exec: 'submit' },
    spike: { executor: 'constant-arrival-rate', rate: SPIKE_RATE, timeUnit: '1s', duration: SPIKE_DURATION, startTime: addSeconds(STEADY_DURATION, 30), preAllocatedVUs: 150, maxVUs: 800, exec: 'submit' },
  },
  // the SLOs, judged per scenario (k6 tags every request with its scenario): the steady load and the spike are different questions
  thresholds: {
    'http_req_duration{name:submit,scenario:steady}': ['p(95)<300'],
    'http_req_failed{name:submit,scenario:steady}': ['rate<0.01'],
    'http_req_duration{name:submit,scenario:spike}': ['p(95)<300'],
    'http_req_failed{name:submit,scenario:spike}': ['rate<0.01'],
    e2e_decision_ms: ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function addSeconds(duration, extra) {
  const m = /^(\d+)(s|m)$/.exec(duration);
  const secs = Number(m[1]) * (m[2] === 'm' ? 60 : 1) + extra;
  return `${secs}s`;
}

function application() {
  const a = JSON.parse(JSON.stringify(template));
  a.requestedLimit = [1000, 2500, 5000, 8000, 12000][Math.floor(Math.random() * 5)];
  a.employment.annualIncome = 30000 + Math.floor(Math.random() * 120000);
  a.bureau.score = 540 + Math.floor(Math.random() * 300);
  return a;
}

export function submit() {
  const headers = { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4(), 'X-Correlation-Id': `k6-${uuidv4()}` };
  const t0 = Date.now();
  const res = http.post(`${GATEWAY}/v1/applications`, JSON.stringify(application()), { headers, tags: { name: 'submit' } });
  const ok = check(res, { 'accepted (202)': (r) => r.status === 202 });
  if (!ok) return;
  submitted.add(1);
  if (Math.floor(Math.random() * SAMPLE_EVERY) !== 0) return;
  const id = res.json('applicationId');
  const deadline = t0 + 10000;
  while (Date.now() < deadline) {
    const s = http.get(`${GATEWAY}/v1/applications/${id}/status`, { tags: { name: 'status' } });
    const status = s.status === 200 ? s.json('status') : null;
    if (status && status !== 'SUBMITTED' && status !== 'RECEIVED' && status !== 'PENDING') {
      e2e.add(Date.now() - t0);
      decided.add(true);
      return;
    }
    sleep(0.2);
  }
  decided.add(false);
}

export function handleSummary(data) {
  const dir = __ENV.OUT_DIR || 'out';
  const m = (name, stat) => (data.metrics[name] && data.metrics[name].values[stat] !== undefined ? data.metrics[name].values[stat] : null);
  const fmt = (v) => (v === null ? 'n/a' : `${v.toFixed(1)}`);
  const rows = Object.entries(data.metrics)
    .filter(([, v]) => v.thresholds)
    .flatMap(([name, v]) => Object.entries(v.thresholds).map(([expr, t]) => `| ${name} | \`${expr}\` | ${t.ok ? 'PASS' : '**FAIL**'} |`));
  const md = [
    '# Load test summary',
    '',
    `Gateway ${GATEWAY}, steady ${STEADY_RATE} rps for ${STEADY_DURATION}, then spike ${SPIKE_RATE} rps for ${SPIKE_DURATION}.`,
    '',
    '| Threshold metric | Expression | Result |',
    '|---|---|---|',
    ...rows,
    '',
    '| Measure | Value |',
    '|---|---|',
    `| Applications accepted | ${m('applications_submitted', 'count')} |`,
    `| Steady: POST /v1/applications p50 / p95 / p99 (ms) | ${fmt(m('http_req_duration{name:submit,scenario:steady}', 'med'))} / ${fmt(m('http_req_duration{name:submit,scenario:steady}', 'p(95)'))} / ${fmt(m('http_req_duration{name:submit,scenario:steady}', 'p(99)'))} |`,
    `| Steady: failed submissions | ${fmt(100 * (m('http_req_failed{name:submit,scenario:steady}', 'rate') || 0))} % |`,
    `| Spike: POST /v1/applications p50 / p95 / p99 (ms) | ${fmt(m('http_req_duration{name:submit,scenario:spike}', 'med'))} / ${fmt(m('http_req_duration{name:submit,scenario:spike}', 'p(95)'))} / ${fmt(m('http_req_duration{name:submit,scenario:spike}', 'p(99)'))} |`,
    `| Spike: failed submissions | ${fmt(100 * (m('http_req_failed{name:submit,scenario:spike}', 'rate') || 0))} % |`,
    `| End to end submit to decided p50 / p95 / p99 (ms), sample 1 in ${SAMPLE_EVERY} | ${fmt(m('e2e_decision_ms', 'med'))} / ${fmt(m('e2e_decision_ms', 'p(95)'))} / ${fmt(m('e2e_decision_ms', 'p(99)'))} |`,
    `| Sampled applications decided within 10 s | ${fmt(100 * (m('e2e_decided_within_10s', 'rate') || 0))} % |`,
    '',
  ].join('\n');
  return { [`${dir}/summary.md`]: md, [`${dir}/summary.json`]: JSON.stringify(data, null, 2), stdout: md };
}
