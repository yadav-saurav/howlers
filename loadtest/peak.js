// Short capacity probe (k6): anonymous read mix at stepped arrival rates, to find where latency starts to bend.
// Read-only (no logins, no writes, no search because of its per-IP limit). About 90 seconds with the default RATES=50,150,300.
// Run: SCRIPT=peak.js sh loadtest/run.sh   (see docs/PERFORMANCE.md)
import http from 'k6/http';
import { check } from 'k6';

const BASE = (__ENV.BASE_URL || 'http://host.docker.internal:8088').replace(/\/$/, '') + '/api/v1';

const RATES = (__ENV.RATES || "50,150,300").split(",").map(Number);
const STEP = 25;
export const options = { scenarios: {}, thresholds: {}, summaryTrendStats: ["med", "p(95)", "p(99)", "max"] };
RATES.forEach((rate, i) => {
  options.scenarios[`r${rate}`] = {
    executor: "constant-arrival-rate", rate, timeUnit: "1s", duration: `${STEP}s`, startTime: `${i * (STEP + 5)}s`,
    preAllocatedVUs: Math.min(200, rate), maxVUs: 300,
  };
  options.thresholds[`http_req_duration{scenario:r${rate}}`] = ["max>=0"];
  options.thresholds[`http_req_failed{scenario:r${rate}}`] = ["rate>=0"];
  options.thresholds[`dropped_iterations{scenario:r${rate}}`] = ["count>=0"];
});

const pick = (a) => a[Math.floor(Math.random() * a.length)];

export function setup() {
  const feed = http.get(`${BASE}/feed?tab=popular&limit=25`).json('items') || [];
  const comms = http.get(`${BASE}/communities?sort=popular&size=25`).json('items') || [];
  return { postIds: feed.map((p) => p.id), slugs: comms.map((c) => c.slug).filter((s) => s !== 'loadtest') };
}

export default function (data) {
  const r = Math.random();
  let res;
  if (r < 0.25) res = http.get(`${BASE}/feed?tab=popular&limit=20`, { tags: { endpoint: 'feed_popular' } });
  else if (r < 0.45) res = http.get(`${BASE}/communities/${pick(data.slugs)}/posts?sort=hot`, { tags: { endpoint: 'community_posts' } });
  else if (r < 0.7) res = http.get(`${BASE}/posts/${pick(data.postIds)}`, { tags: { endpoint: 'post_detail' } });
  else res = http.get(`${BASE}/posts/${pick(data.postIds)}/comments?sort=best&limit=20`, { tags: { endpoint: 'post_comments' } });
  check(res, { ok: (x) => x.status === 200 });
}

export function handleSummary(data) {
  const m = data.metrics;
  const rows = RATES.map((rate) => {
    const d = m[`http_req_duration{scenario:r${rate}}`];
    const f = m[`http_req_failed{scenario:r${rate}}`];
    const dr = m[`dropped_iterations{scenario:r${rate}}`];
    const n = f ? f.values.passes + f.values.fails : 0;
    return `| ${rate} | ${(n / STEP).toFixed(1)} | ${d.values.med.toFixed(1)} | ${d.values["p(95)"].toFixed(1)} | ${d.values["p(99)"].toFixed(1)} | ${d.values.max.toFixed(1)} | ${(f.values.rate * 100).toFixed(2)} | ${dr ? dr.values.count : 0} |`;
  });
  const out = [`Howlers capacity probe, ${new Date().toISOString()}`, "",
    "| Target req/s | Achieved req/s | p50 ms | p95 ms | p99 ms | max ms | Errors % | Dropped |",
    "|---:|---:|---:|---:|---:|---:|---:|---:|", ...rows, ""].join('\n');
  return { stdout: out, "/work/results/peak-latest.md": out };
}
