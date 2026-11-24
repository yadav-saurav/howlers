# Performance and load testing

A repeatable k6 load test for the production-like Docker stack (nginx + backend + PostgreSQL + Redis + S3-compatible
storage) at http://localhost:8088, plus a short capacity probe. Everything lives in `loadtest/`.

## Rerun

From the repo root, with the Docker stack up (`docker compose up`) and demo seeding on:

```bash
sh loadtest/run.sh                             # main test, about 4.5 minutes
SCALE=0.2 sh loadtest/run.sh                   # one-minute smoke run of the same mix
SCRIPT=peak.js sh loadtest/run.sh              # 90 s read-only capacity probe (RATES=50,150,300 by default)
SCRIPT=peak.js RATES=200,250,300 sh loadtest/run.sh
SCRIPT=cleanup.js sh loadtest/run.sh           # delete the comments the test wrote
DOCKER=docker sh loadtest/count-db-work.sh     # rough Postgres transactions and rows per request
```

On Windows with Git Bash, if `docker` is not on PATH, prefix with
`DOCKER="/c/Users/<you>/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe"`. `run.sh` runs the pinned
`grafana/k6:0.54.0` image, mounts `loadtest/` at `/work` and targets `http://host.docker.internal:8088`
(override with `BASE_URL`). Results go to `loadtest/results/`: `latest.md` (the per-endpoint table), a timestamped
copy, and a full JSON summary (git-ignored).

## What the main test does (`loadtest/howlers.js`)

| Scenario | Load | Requests |
|---|---|---|
| `browse` (anonymous) | ramps 0 → 15 VUs (60 s) → 45 VUs (90 s), holds 90 s, ramps down 30 s; 0.5–3 s think time | feed (popular / latest / top week), community list + trending (30 % of iterations), community detail + posts (hot/new/top), post detail + comments (best/new/top) |
| `search` (anonymous) | fixed 0.75 req/s for 270 s | post search, community search, suggest |
| `members` (signed in) | 5 VUs, one per demo user (`mira_k`, `dev_ostrander`, `nadia_sec`, `paperback_jo`, `tunde_builds`) | "for you" feed, a vote toggle every ~7 s, a comment about once a minute, a vote on their own comment, an occasional search |

Peak concurrency is 50 VUs (45 + 5), which keeps the owner's machine usable.

**Test data.** Writes only go to the dedicated community **c/loadtest** ("Load test (automated)", description: holds
data written by the automated load test, not real activity). `setup()` creates it as `mira_k` on the first run, along
with one thread, "Load test thread (automated, not real activity)", and reuses both afterwards. Votes alternate between
1 and 0 and are cleared at the end, so scores never drift. A full run writes about 25 short comments, all labelled
"Automated load-test comment from …"; `cleanup.js` soft-deletes them. Seeded demo posts are read but never written to.

**Rate limits that shape the mix** (backend `RateLimiter`; nginx has no `limit_req`; all k6 traffic comes from one IP):

| Limit | Value | How the test stays under it |
|---|---|---|
| Login | 30 / IP and 10 / account per 10 min | each demo user logs in once, in `setup()`, and the access token (15 min) is reused |
| Search | 60 / min per IP when signed out, per user when signed in | anonymous search runs at a fixed 0.75 req/s; members search every third loop |
| Comments | 30 / user per 10 min | about one comment per user per minute |
| Community creation | 5 / user per day | only on the first run |

So 429s are not expected, and none occurred. Running the main test more than three times within 10 minutes would hit
the per-account login limit.

## Results (2026-11-24)

Machine: Windows 11 laptop, 12 logical CPUs, Docker Desktop VM with 7.6 GiB RAM; k6 runs in the same Docker VM as the
stack, so the load generator competes for the same CPUs. Demo dataset: 10 communities, 14 seeded posts, about 80
comments. Default profile (`SPRING_PROFILES_ACTIVE=default`), feed cache on (30 s TTL), Hikari pool 15.

### Main test: 284 s, 11 759 requests, 41.3 req/s overall, 0 errors, all 8 021 checks passed

| Endpoint | Requests | req/s | p50 ms | p95 ms | p99 ms | max ms | Errors % |
|---|---:|---:|---:|---:|---:|---:|---:|
| feed_popular | 1001 | 3.52 | 10.4 | 19.5 | 42.4 | 129.3 | 0 |
| feed_latest | 476 | 1.67 | 10.5 | 21.1 | 37.3 | 172.2 | 0 |
| feed_top_week | 476 | 1.67 | 10.2 | 20.0 | 31.8 | 73.4 | 0 |
| communities_list | 555 | 1.95 | 7.6 | 13.3 | 27.7 | 39.3 | 0 |
| communities_trending | 555 | 1.95 | 8.0 | 13.3 | 29.4 | 50.2 | 0 |
| community_detail | 1953 | 6.87 | 9.1 | 15.0 | 29.9 | 113.0 | 0 |
| community_posts | 1953 | 6.87 | 10.2 | 18.3 | 30.3 | 123.8 | 0 |
| post_detail | 1953 | 6.87 | 8.6 | 14.0 | 28.3 | 96.8 | 0 |
| post_comments | 1953 | 6.87 | 8.0 | 13.7 | 26.4 | 54.7 | 0 |
| search_posts | 134 | 0.47 | 8.7 | 18.7 | 46.5 | 64.2 | 0 |
| search_communities | 36 | 0.13 | 9.5 | 14.6 | 31.0 | 39.3 | 0 |
| search_suggest | 33 | 0.12 | 8.5 | 12.4 | 13.5 | 13.9 | 0 |
| member_feed_foryou | 184 | 0.65 | 12.5 | 29.4 | 53.9 | 135.5 | 0 |
| member_post_vote | 184 | 0.65 | 14.1 | 28.8 | 68.2 | 93.9 | 0 |
| member_comment_create | 25 | 0.09 | 19.8 | 35.8 | 48.8 | 52.5 | 0 |
| member_comment_vote | 184 | 0.65 | 13.8 | 25.1 | 53.4 | 98.5 | 0 |
| member_search | 60 | 0.21 | 9.4 | 20.7 | 23.0 | 25.2 | 0 |

All requests together: p50 9.2 ms, p95 17.8 ms, p99 34.6 ms, max 340 ms.

Container CPU during the run (`docker stats`, sampled every ~10 s; 100 % = one core): backend average 30 %, peak
100 %; Postgres average 10 %, peak 24 %; nginx average 3 %; Redis average 2 %. Backend memory stayed at about 690 MiB.

### Capacity probe (`loadtest/peak.js`): anonymous read mix, 25 s per step

The mix is 25 % popular feed, 20 % community posts, 25 % post detail, 30 % post comments, at a fixed arrival rate.

| Run | Target req/s | Achieved req/s | p50 ms | p95 ms | p99 ms | max ms | Errors % | Dropped |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 50 | 50.0 | 14.1 | 39.5 | 85.1 | 334.1 | 0 | 0 |
| 1 | 150 | 150.0 | 9.7 | 43.2 | 83.1 | 254.9 | 0 | 0 |
| 1 | 300 | 295.8 | 40.2 | 332.6 | 2764.3 | 3672.2 | 0 | 107 |
| 2 | 200 | 200.0 | 8.4 | 37.8 | 84.5 | 219.3 | 0 | 0 |
| 2 | 250 | 250.0 | 8.9 | 36.0 | 75.9 | 186.5 | 0 | 0 |
| 2 | 300 | 300.0 | 27.8 | 86.5 | 146.6 | 271.2 | 0 | 0 |
| 3 | 300 | 230.6 | 230.0 | 2555.1 | 4779.2 | 6554.1 | 0 | 1736 |
| 3 | 400 | 333.8 | 205.5 | 1107.5 | 10082.0 | 31827.9 | 0.40 | 1657 |
| 3 | 500 | 444.0 | 106.8 | 1245.9 | 1926.6 | 3350.6 | 0 | 1400 |

Up to **250 req/s** the stack is comfortable (p95 under 45 ms). Around **300 req/s** it is at the edge: one run held
(p95 87 ms), two did not (p99 in seconds, k6 dropping iterations because responses queued). Above that, latency is
in seconds. During the 300 req/s step the backend used up to about 4.7 cores, Postgres about 1.5 and nginx 0.7, with
k6 running alongside in the same VM.

### Database work per request (`loadtest/count-db-work.sh`)

Measured from `pg_stat_database` across 50 sequential anonymous requests per endpoint:

| Endpoint | Transactions / req | Rows read / req |
|---|---:|---:|
| `GET /posts/4` | 1.8 | 58 |
| `GET /posts/4/comments?sort=best` | 1.7 | 178 |
| `GET /feed?tab=popular` | 1.7 | 116 |
| `GET /feed?tab=latest` | 1.7 | 123 |
| `GET /communities/programming/posts?sort=hot` | 1.7 | 117 |
| `GET /communities/programming` | 1.6 | 157 |
| `GET /communities/trending` | 1.5 | 176 |
| `GET /communities?sort=popular` | 1.8 | 85 |

Each read is one or two transactions with a flat row count, which is consistent with the code review below: no N+1
pattern on these paths.

### Backend metrics

Prometheus (`/actuator/prometheus`) is only exposed in the `prod` profile, on the management port 8081 (D-026). The
local stack runs the default profile, which exposes only `health` and `info`, so backend-side histograms were not
collected; the numbers above are client-side (k6) plus `docker stats` and `pg_stat_*`. For a production-profile run,
`deploy/prometheus.yml` and the Grafana dashboard in `deploy/grafana/` already chart `http.server.requests` p95.

## Findings and bottlenecks

1. **No query-level bottleneck at this data size.** The hot read paths are already batched: feed and community
   pages fetch an id page and hydrate it with one query per concern (`PostQueryService.hydrate`: base rows, authors,
   viewer votes, saved, moderated communities); comment trees load level by level with a windowed query, not per
   parent (`CommentService`). Every lookup the test touches has a matching index (`ix_posts_hot`,
   `ix_posts_community_hot`, `ix_comments_post_top`, `ix_comments_parent`, `ux_communities_slug` on `lower(slug)`,
   which `CommunityRepository.findBySlug` uses, and `ux_users_username` on `lower(username)`). The sequential scans in
   `pg_stat_user_tables` are on tables with 10–100 rows, where Postgres prefers them; they say nothing about scale.
   No code or index change was made.

2. **Feed-cache circuit breaker trips under overload (real, documented, not changed).** At 300+ req/s the backend logged
   `Feed cache: Redis unavailable, serving feeds uncached (QueryTimeoutException)`. Redis itself was idle (about 2 %
   CPU); the 500 ms Redis client timeout (`spring.data.redis.timeout`) fired because the saturated JVM could not
   service the reply in time. `FeedCache` then bypasses Redis for 30 s after a **single** failure, so every feed
   request goes to Postgres exactly when the system is busiest, which deepens the overload. Recommended contained
   change: trip only after several consecutive failures (or a failure rate over a short window) and keep the 30 s
   back-off. It was left alone here because it changes the D-025 behaviour and its test, and the stack running on 8088
   would not pick up a code change without a rebuild.

3. **Throughput ceiling of roughly 250–300 req/s on this machine**, bound by backend CPU (Tomcat + JSON + JDBC per
   request) with Postgres second, not by a single slow endpoint. With the load generator in the same Docker VM the
   real ceiling is likely somewhat higher. At this level, the most effective levers are: serve anonymous first-page
   reads (feed, community posts, post detail) with a short `Cache-Control: public, max-age` so nginx or a CDN can absorb
   them; raise the Hikari pool only together with Postgres `max_connections` (currently 100) if profiling shows pool
   waits; and run more than one backend instance behind nginx (the rate limiter and feed cache are already Redis-backed,
   so instances share state).

4. **Scale risk to watch: `/communities/trending`.** It aggregates the last 7 days of `community_members` and `posts`
   on every call (13 ms p95 today). With real volume this grows with weekly activity; it is a good candidate for the
   same short Redis cache the feeds use, or for a periodically refreshed table.

5. **Signed-in writes are cheap.** Vote toggles p95 29 ms and comment creation p95 36 ms, including the spam checks,
   hot-rank update and notification fan-out.

Re-measure after any of the changes above with the same commands, ideally with a larger dataset (thousands of
posts and comments) so index and plan behaviour matches production.
