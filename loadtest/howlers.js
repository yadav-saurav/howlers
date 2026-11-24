// Howlers load test (k6). See docs/PERFORMANCE.md for how to run it and the recorded results.
//
// Gentle by design: anonymous browsing ramps to 45 VUs over ~2.5 minutes, plus 5 signed-in demo users who vote and
// comment at low rates. Writes go only to the dedicated test community c/loadtest (created on first run and labelled
// as load-test data); votes there are toggled on and off, so repeated runs leave scores unchanged.
//
// Rate limits that shape the mix (backend RateLimiter, all traffic comes from one IP):
//   login 30/IP and 10/account per 10 min -> each demo user logs in once, in setup()
//   search 60/min per IP for anonymous users -> anonymous search runs at a fixed 0.75 req/s
//   comments 30/user per 10 min -> each signed-in user comments about once a minute
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

const BASE = (__ENV.BASE_URL || 'http://host.docker.internal:8088').replace(/\/$/, '') + '/api/v1';
const PASSWORD = __ENV.DEMO_PASSWORD || 'howlers-demo-1';
const USERS = ['mira_k', 'dev_ostrander', 'nadia_sec', 'paperback_jo', 'tunde_builds'];
const SLUG = 'loadtest';
const SCALE = Number(__ENV.SCALE || 1); // 0.2 for a 1-minute smoke run

const d = (s) => `${Math.max(5, Math.round(s * SCALE))}s`;

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      exec: 'browse',
      startVUs: 0,
      stages: [
        { duration: d(60), target: 15 },
        { duration: d(90), target: 45 },
        { duration: d(90), target: 45 },
        { duration: d(30), target: 0 },
      ],
      gracefulRampDown: '10s',
    },
    search: {
      executor: 'constant-arrival-rate',
      exec: 'search',
      rate: 3,
      timeUnit: '4s', // 0.75 req/s, under the 60/min anonymous search limit
      duration: d(270),
      preAllocatedVUs: 2,
      maxVUs: 4,
    },
    members: {
      executor: 'per-vu-iterations',
      exec: 'members',
      vus: USERS.length,
      iterations: 1,
      maxDuration: d(290),
      startTime: '5s',
    },
  },
  thresholds: {
    'http_req_failed{scenario:browse}': ['rate<0.01'],
    'http_req_duration{scenario:browse}': ['p(95)<800'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const ENDPOINTS = [
  'feed_popular', 'feed_latest', 'feed_top_week', 'communities_list', 'communities_trending', 'community_detail',
  'community_posts', 'post_detail', 'post_comments', 'search_posts', 'search_communities', 'search_suggest',
  'member_feed_foryou', 'member_post_vote', 'member_comment_create', 'member_comment_vote', 'member_search',
];
// Per-endpoint sub-metrics show up in the summary only when a threshold references them.
for (const e of ENDPOINTS) {
  options.thresholds[`http_req_duration{endpoint:${e}}`] = ['max>=0'];
  options.thresholds[`http_req_failed{endpoint:${e}}`] = ['rate>=0'];
}

const json = { 'Content-Type': 'application/json' };
const auth = (t) => ({ ...json, Authorization: `Bearer ${t}` });
const pick = (a) => a[Math.floor(Math.random() * a.length)];
const get = (path, endpoint, token) =>
  http.get(BASE + path, { headers: token ? auth(token) : {}, tags: { endpoint } });

export function setup() {
  const tokens = {};
  for (const u of USERS) {
    const r = http.post(`${BASE}/auth/login`, JSON.stringify({ login: u, password: PASSWORD }), { headers: json, tags: { endpoint: 'setup' } });
    if (r.status === 200) tokens[u] = r.json('accessToken');
    else console.warn(`login ${u} -> ${r.status}`);
  }
  const owner = tokens.mira_k;
  if (!owner) throw new Error('could not log in as mira_k');

  // The dedicated, clearly labelled test community.
  if (http.get(`${BASE}/communities/${SLUG}`, { tags: { endpoint: 'setup' } }).status === 404) {
    const r = http.post(`${BASE}/communities`, JSON.stringify({
      slug: SLUG,
      name: 'Load test (automated)',
      description: 'Holds data written by the automated load test in loadtest/howlers.js. Not real activity; safe to delete.',
      visibility: 'PUBLIC',
      tags: ['loadtest'],
      rules: [],
    }), { headers: auth(owner), tags: { endpoint: 'setup' } });
    if (r.status !== 201) throw new Error(`create c/${SLUG} -> ${r.status} ${r.body}`);
  }
  for (const u of Object.keys(tokens)) {
    http.post(`${BASE}/communities/${SLUG}/membership`, null, { headers: auth(tokens[u]), tags: { endpoint: 'setup' } });
  }

  // One thread per run would pile up; reuse the newest test thread if there is one.
  let postId = null;
  const posts = http.get(`${BASE}/communities/${SLUG}/posts?sort=new`, { tags: { endpoint: 'setup' } });
  if (posts.status === 200 && posts.json('items').length) postId = posts.json('items')[0].id;
  if (!postId) {
    const r = http.post(`${BASE}/posts`, JSON.stringify({
      communitySlug: SLUG,
      type: 'TEXT',
      title: 'Load test thread (automated, not real activity)',
      bodyHtml: '<p>Comments and votes on this post are written by the automated load test.</p>',
    }), { headers: auth(owner), tags: { endpoint: 'setup' } });
    if (r.status !== 200 && r.status !== 201) throw new Error(`create test post -> ${r.status} ${r.body}`);
    postId = r.json('id');
  }

  // Real ids to browse, from the public popular feed and community list.
  const feed = http.get(`${BASE}/feed?tab=popular&limit=25`, { tags: { endpoint: 'setup' } }).json('items') || [];
  const comms = http.get(`${BASE}/communities?sort=popular&size=25`, { tags: { endpoint: 'setup' } }).json('items') || [];
  return {
    tokens,
    postId,
    postIds: feed.map((p) => p.id),
    slugs: comms.map((c) => c.slug).filter((s) => s !== SLUG),
  };
}

// Anonymous reader: a feed, a community, then a post and its comments, with think time.
export function browse(data) {
  group('feeds', () => {
    const tab = pick(['feed_popular', 'feed_popular', 'feed_latest', 'feed_top_week']);
    const q = { feed_popular: 'tab=popular', feed_latest: 'tab=latest', feed_top_week: 'tab=top&t=week' }[tab];
    check(get(`/feed?${q}&limit=20`, tab), { 'feed 200': (r) => r.status === 200 });
  });
  sleep(0.5 + Math.random());
  if (Math.random() < 0.3) {
    get('/communities?sort=popular&page=0&size=20', 'communities_list');
    get('/communities/trending?limit=5', 'communities_trending');
  }
  const slug = pick(data.slugs);
  group('community', () => {
    check(get(`/communities/${slug}`, 'community_detail'), { 'community 200': (r) => r.status === 200 });
    get(`/communities/${slug}/posts?sort=${pick(['hot', 'new', 'top'])}`, 'community_posts');
  });
  sleep(0.5 + Math.random());
  const id = pick(data.postIds);
  group('post', () => {
    check(get(`/posts/${id}`, 'post_detail'), { 'post 200': (r) => r.status === 200 });
    check(get(`/posts/${id}/comments?sort=${pick(['best', 'new', 'top'])}&limit=20`, 'post_comments'), { 'comments 200': (r) => r.status === 200 });
  });
  sleep(1 + Math.random() * 2);
}

const TERMS = ['rust', 'go', 'career', 'books', 'security', 'design', 'startup', 'fitness', 'science', 'movie', 'interview', 'privacy'];

// Anonymous search at a fixed rate under the per-IP limit.
export function search() {
  const r = Math.random();
  if (r < 0.6) get(`/search?q=${pick(TERMS)}&type=posts&sort=relevance`, 'search_posts');
  else if (r < 0.8) get(`/search?q=${pick(TERMS)}&type=communities`, 'search_communities');
  else get(`/search/suggest?q=${pick(TERMS).slice(0, 3)}`, 'search_suggest');
}

// Signed-in demo user: browse their feed, toggle votes in c/loadtest, comment about once a minute.
export function members(data) {
  const user = USERS[(__VU - 1) % USERS.length];
  const token = data.tokens[user];
  if (!token) return;
  const end = Date.now() + 270 * 1000 * SCALE;
  let n = 0;
  let lastComment = 0;
  const myComments = [];
  while (Date.now() < end) {
    n++;
    get('/feed?tab=foryou&limit=20', 'member_feed_foryou', token);
    sleep(2 + Math.random() * 2);
    const v = http.post(`${BASE}/posts/${data.postId}/vote`, JSON.stringify({ value: n % 2 ? 1 : 0 }), { headers: auth(token), tags: { endpoint: 'member_post_vote' } });
    check(v, { 'vote 200': (r) => r.status === 200 });
    if (Date.now() - lastComment > 60_000) {
      lastComment = Date.now();
      const c = http.post(`${BASE}/posts/${data.postId}/comments`, JSON.stringify({
        body: `Automated load-test comment from ${user}, round ${n} at ${new Date().toISOString()}.`,
      }), { headers: auth(token), tags: { endpoint: 'member_comment_create' } });
      check(c, { 'comment 2xx': (r) => r.status === 200 || r.status === 201 });
      if (c.status === 200 || c.status === 201) myComments.push(c.json('id'));
    }
    if (myComments.length) {
      const cid = pick(myComments);
      http.post(`${BASE}/comments/${cid}/vote`, JSON.stringify({ value: n % 2 ? 1 : 0 }), { headers: auth(token), tags: { endpoint: 'member_comment_vote' } });
    }
    if (n % 3 === 0) get(`/search?q=${pick(TERMS)}&type=posts`, 'member_search', token);
    sleep(3 + Math.random() * 3);
  }
  // Leave every vote cleared, so runs never change scores.
  http.post(`${BASE}/posts/${data.postId}/vote`, JSON.stringify({ value: 0 }), { headers: auth(token), tags: { endpoint: 'teardown' } });
  for (const cid of myComments) {
    http.post(`${BASE}/comments/${cid}/vote`, JSON.stringify({ value: 0 }), { headers: auth(token), tags: { endpoint: 'teardown' } });
  }
}

function endpointTable(data) {
  const rows = [];
  const dur = data.state.testRunDurationMs / 1000;
  for (const e of ENDPOINTS) {
    const m = data.metrics[`http_req_duration{endpoint:${e}}`];
    const f = data.metrics[`http_req_failed{endpoint:${e}}`];
    if (!m || !m.values.count && !f) continue;
    const count = f ? f.values.passes + f.values.fails : 0;
    rows.push({
      endpoint: e,
      requests: count,
      rps: +(count / dur).toFixed(2),
      p50: +m.values.med.toFixed(1),
      p95: +m.values['p(95)'].toFixed(1),
      p99: +m.values['p(99)'].toFixed(1),
      max: +m.values.max.toFixed(1),
      errorRate: f ? +(f.values.rate * 100).toFixed(2) : 0,
    });
  }
  return rows;
}

export function handleSummary(data) {
  const rows = endpointTable(data);
  const total = data.metrics.http_reqs.values;
  const lines = [
    `Howlers load test, ${new Date().toISOString()}, ${(data.state.testRunDurationMs / 1000).toFixed(0)} s, ${total.count} requests (${total.rate.toFixed(1)} req/s overall)`,
    '',
    '| Endpoint | Requests | req/s | p50 ms | p95 ms | p99 ms | max ms | Errors % |',
    '|---|---:|---:|---:|---:|---:|---:|---:|',
    ...rows.map((r) => `| ${r.endpoint} | ${r.requests} | ${r.rps} | ${r.p50} | ${r.p95} | ${r.p99} | ${r.max} | ${r.errorRate} |`),
    '',
  ].join('\n');
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: false }) + '\n\n' + lines,
    [`/work/results/summary-${stamp}.json`]: JSON.stringify({ endpoints: rows, metrics: data.metrics }, null, 2),
    [`/work/results/endpoints-${stamp}.md`]: lines,
    '/work/results/latest.md': lines,
  };
}
