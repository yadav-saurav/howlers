# Deployment

How to run Howlers in production on a single VPS with Docker Compose behind Caddy (automatic TLS). Everything here
uses files in `deploy/`. Decisions behind it: D-023 (storage), D-025 (feed cache), D-026 (observability and guard rails).

> **Verification status.** The compose files, Caddyfile, Prometheus and Grafana files were validated as YAML/JSON and
> reviewed, but **not executed**: Docker isn't installed on the development machine. The backend, its tests and the
> frontend build were run. Treat the first deploy as a rehearsal on a staging host. Update (D-029): both compose files pass `docker compose config`, and the `clamav` service definition was run in a throwaway compose project (healthy, EICAR detected, see section 10).

## 1. What runs

| Service | Image | Purpose | Exposed |
|---|---|---|---|
| `caddy` | `caddy:2.8-alpine` | TLS (Let's Encrypt), HTTP→HTTPS, HTTP/2+3, HSTS, gzip/zstd | **80, 443** (the only published ports) |
| `web` | built from `frontend/` | nginx: SPA + reverse proxy for `/api`, `/ws`, Swagger | internal |
| `backend` | built from `backend/` | Spring Boot API, profile `prod`; 8080 app, 8081 management/metrics | internal |
| `postgres` | `postgres:17-alpine` | primary database (`pgdata` volume) | internal |
| `pgbackup` | `postgres:17-alpine` | nightly `pg_dump` to `./backups` | internal |
| `redis` | `redis:7-alpine` | rate limits, feed cache (no persistence, password) | internal |
| `clamav` | `clamav/clamav:1.4.3` | antivirus for uploads (clamd INSTREAM, D-029); ~1 GB RAM, see section 10 | internal |
| `s3` | `chrislusf/seaweedfs:4.55` | S3-compatible object storage for uploads; one access identity, anonymous access refused; the backend creates the private bucket at start | internal |

> **Why SeaweedFS and not MinIO:** MinIO stopped publishing public images (Docker Hub reports the repository missing and
> quay.io answers 401 to anonymous pulls), so the bundled object store is SeaweedFS. The backend only speaks the S3 API, so
> any S3-compatible service works (see below).
| `prometheus`, `grafana` | optional (`--profile monitoring`) | metrics and dashboard | internal (Grafana via Caddy if you enable it) |

Request path: browser → Caddy (TLS) → nginx (`web`) → backend. nginx keeps Caddy's `X-Forwarded-Proto: https`, and the
backend (`server.forward-headers-strategy: native`) trusts private-network proxies, so it sees HTTPS requests: secure
cookies, HSTS and correct client IPs for rate limits.

## 2. First deploy

1. A VPS with 2 vCPU / 4 GB RAM or more (the JVM takes ~1–1.5 GB), Docker Engine and the Compose plugin, a DNS
   `A`/`AAAA` record for your domain, and ports 80/443 open.
2. Clone the repo, then `cp deploy/.env.example deploy/.env && chmod 600 deploy/.env` and fill in **every** value.
   Generate secrets with `openssl rand -base64 48` (JWT) and `openssl rand -hex 32` (others).
3. `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build`
4. Watch `docker compose -f deploy/docker-compose.prod.yml logs -f backend`. If a secret is missing or a dev default,
   the backend stops with a list of every problem ("Refusing to start the prod profile…"); fix `deploy/.env` and rerun.
5. Open `https://<your-domain>`, register, then promote yourself to admin once:
   `docker compose -f deploy/docker-compose.prod.yml exec postgres psql -U howlers -d howlers -c "UPDATE users SET role='ADMIN' WHERE username='you'"`.

Keep `SEED_DEMO_DATA=false` for a real community. If you enable it for a demo instance, the UI shows a "Demo data" notice.

## 3. Environment reference

Required in production (the compose file refuses to start without them, and the backend's `ProductionSafetyCheck`
re-checks the important ones):

| Variable | Meaning |
|---|---|
| `HOWLERS_DOMAIN`, `ACME_EMAIL` | public host name; email for certificate notices |
| `DATABASE_PASSWORD` (+ `DATABASE_USER`) | PostgreSQL credentials (≥ 12 chars, not a default) |
| `REDIS_PASSWORD` | Redis `requirepass` |
| `JWT_SECRET` | base64 of ≥ 32 random bytes; signs access tokens and download grants. Rotating it signs everyone out |
| `FILE_VISITOR_SALT` | server secret for hashing share-link visitors (no IPs are stored) |
| `ACCOUNT_SECRETS_KEY` | base64 of 32 random bytes (`openssl rand -base64 32`); encrypts two-factor secrets (D-042). Keep it with your backups |
| `FUNDING_DEMO_WEBHOOK_SECRET` | ≥ 16 chars; HMAC for demo funding webhooks (D-022) |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET` | object storage credentials (the `s3` service is configured with the same pair) |
| `MAIL_MODE`, `MAIL_FROM_ADDRESS` (or the older `MAIL_FROM`) | `brevo` or `smtp`; the from address must be verified with the provider (see "Email with Brevo") |
| `BREVO_API_KEY` | for `MAIL_MODE=brevo` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD` | for `MAIL_MODE=smtp` |

Optional:

| Variable | Default | Meaning |
|---|---|---|
| `MAIL_FROM_NAME`, `MAIL_REPLY_TO` | `Howlers`, empty | display name and reply-to address for transactional email |
| `BREVO_REMOVE_TRACKING_PIXEL` | `false` | Brevo Enterprise only: send without the open pixel |
| `ANTHROPIC_API_KEY`, `AI_MODEL`, `AI_EFFORT` | empty, empty, `medium` | Community AI; without a key, answers are offline cited summaries |
| `ELEVENLABS_API_KEY` | empty | podcast audio generation (D-036); without it the studio says generation is unavailable. Server-side only |
| `ELEVENLABS_MODEL`, `ELEVENLABS_VOICE_A`, `ELEVENLABS_VOICE_B` | `eleven_multilingual_v2`, Matilda `XrExE9yKIg1WjnnlVkGX`, George `JBFqnCBsd6RMkjVDRZzb` | TTS model and the premade voices for HOST A / HOST B |
| `TTS_PROVIDER`, `TTS_DAILY_CHARACTER_BUDGET`, `TTS_MAX_CHARS_PER_EPISODE` | `auto`, `100000`, `60000` | `auto`/`elevenlabs`/`none`; characters per community per UTC day; cap per generation |
| `FUNDING_ENABLED` | `true` | moderator funding |
| `FUNDING_PROVIDER` | `demo` | `demo` (simulated) or `stripe` (hosted Checkout, D-034); see "Stripe payments" below |
| `STRIPE_SECRET_KEY` | empty | Stripe secret key, server-side only. `sk_test_…` = test mode. A live key refuses to start without the two settings below |
| `STRIPE_WEBHOOK_SECRET` | empty | signing secret of the webhook endpoint (`whsec_…`); required in production with Stripe |
| `FUNDING_LIVE_MODE`, `FUNDING_COMPLIANCE_ACK` | `false`, empty | both needed before a live key is accepted; the acknowledgement records who signed off the compliance review (D-034) |
| `SEED_DEMO_DATA` | `false` | fictional demo content |
| `WEBAUTHN_RP_ID`, `WEBAUTHN_ORIGINS` | host and origin of `APP_PUBLIC_URL` | passkey relying party id and allowed origins (D-041); see section 13 before changing |
| `NEW_SIGNIN_ALERTS` | `true` | "New sign-in" emails for unfamiliar devices (users can also opt out) |
| `DATABASE_POOL_SIZE` | 15 | Hikari pool size |
| `FEED_CACHE_ENABLED`, `FEED_CACHE_TTL` | `true`, `PT30S` | first-page feed id cache (D-025) |
| `RATE_LIMIT_ENABLED` | `true` | Redis rate limiter |
| `SPAM_HEURISTICS_ENABLED` | `true` | write-time spam checks (D-021) |
| `MAX_MEDIA_MB`, `MAX_FILE_MB`, `FILE_QUOTA_MB` | 500, 500, 1024 | upload limits (keep below nginx's `client_max_body_size 600m`) |
| `MANAGEMENT_PORT` | 8081 | actuator/metrics port (never publish it) |
| `LOG_FORMAT` | `ecs` | structured log format: `ecs`, `logstash` or `gelf` |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=70 …` | JVM flags |
| `BACKUP_DIR`, `BACKUP_KEEP_DAYS`, `BACKUP_INTERVAL_SECONDS` | `./backups`, 14, 86400 | database dump schedule |
| `HOWLERS_BACKEND_IMAGE`, `HOWLERS_WEB_IMAGE` | local build | use images built by CI instead of building on the server |

### Email with Brevo (D-038)
The app sends two emails: email verification (link valid 24 h) and password reset (1 h). Both link to `APP_PUBLIC_URL`.
1. **Verify the sender.** In Brevo, *Senders, domains & dedicated IPs*: add the from address and confirm it, or better,
   authenticate the whole domain. Brevo rejects mail from an unverified sender with 400; the backend logs that once per
   message and does not retry.
2. **SPF / DKIM / DMARC.** Domain authentication gives you DNS records: a `brevo-code` TXT record, DKIM (CNAME or TXT),
   and an SPF include (`include:spf.brevo.com` in your existing SPF TXT record; keep a single SPF record). Add a DMARC
   record (`_dmarc` TXT, start with `v=DMARC1; p=none; rua=mailto:...`). Wait until Brevo shows the domain as
   authenticated; without it, verification emails tend to land in spam.
3. **API key.** *SMTP & API > API keys*: create a key for this server only and put it in `BREVO_API_KEY` in
   `deploy/.env` (never in the repo or the frontend). If the account restricts API access by IP, add the server's IP.
4. **Set** `MAIL_MODE=brevo`, `MAIL_FROM_ADDRESS`, `MAIL_FROM_NAME` and optionally `MAIL_REPLY_TO`. The backend logs
   `Mail: Brevo transactional API, from "Howlers" <...>, API key configured` at startup; it never prints the key.
5. **Tracking.** The API can't turn off click tracking per message. The backend marks each recipient
   `contactPixelTrackingConsent: false`, which anonymises opens and clicks when per-contact consent is enabled on the
   account; Enterprise accounts can set `BREVO_REMOVE_TRACKING_PIXEL=true` to drop the open pixel. Check the
   transactional tracking settings in your Brevo account (or ask Brevo support) if you want link rewriting off, since
   it sends one-time links through Brevo's redirect.
6. **Check it.** Register a test account and watch `logs backend` for `Mail sent via brevo: verify-email to a***@...`.
   From a dev machine: put `BREVO_API_KEY`, `MAIL_FROM_ADDRESS` and `MAIL_TEST_TO` in `backend/.env.local` and run
   `./mvnw.cmd test -Dtest=BrevoLiveMailTest -Dhowlers.liveMail=true` (sends one email to `MAIL_TEST_TO`).

**SMTP instead.** `MAIL_MODE=smtp` with `SMTP_HOST=smtp-relay.brevo.com`, `SMTP_PORT=587`, `SMTP_USER` = the SMTP
login shown in Brevo and `SMTP_PASSWORD` = an SMTP key (not the API key) works too, as does any other relay. STARTTLS is
used when offered. Retries, after-commit sending and the templates are the same in both modes.

### Using AWS S3 (or another S3 service) instead of the bundled SeaweedFS
Set `S3_ENDPOINT` to empty (AWS) or the provider's endpoint, `S3_PATH_STYLE=false` for AWS, `S3_REGION`, and credentials
(`S3_ACCESS_KEY`/`S3_SECRET_KEY`, or leave them blank to use an instance role). Remove the `s3` service and the backend's
`s3` dependency, and set `S3_CREATE_BUCKET=false`. Create the bucket with **Block Public Access** on; the API never needs public
objects (every read is proxied, D-023). Grant the key only `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`,
`s3:AbortMultipartUpload` on `arn:aws:s3:::<bucket>/*` and `s3:ListBucket` on the bucket (for health checks).

### nginx + certbot instead of Caddy
Drop the `caddy` service, publish `web` on 127.0.0.1:8088, and put a host nginx in front:
```nginx
server {
    listen 443 ssl http2;
    server_name howlers.example.com;
    ssl_certificate     /etc/letsencrypt/live/howlers.example.com/fullchain.pem;   # certbot --nginx -d howlers.example.com
    ssl_certificate_key /etc/letsencrypt/live/howlers.example.com/privkey.pem;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    client_max_body_size 600m;
    location / {
        proxy_pass http://127.0.0.1:8088;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;       # /ws (STOMP)
        proxy_set_header Connection $connection_upgrade;  # needs the usual map $http_upgrade block
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 1h;
        proxy_request_buffering off;
    }
}
server { listen 80; server_name howlers.example.com; return 301 https://$host$request_uri; }
```
Certbot's systemd timer renews certificates; add `--deploy-hook "systemctl reload nginx"`.

## 4. Volumes and backups

| Volume | Holds | Back up? |
|---|---|---|
| `pgdata` | database | yes, via the `pgbackup` dumps (not by copying the live directory) |
| `s3data` | uploads (images, podcast media, private files, DM images) | yes |
| `caddy_data` | TLS certificates and ACME account | recommended (avoids re-issuing and rate limits) |
| `promdata`, `grafanadata` | metrics, Grafana state | optional |

- **Database.** `pgbackup` writes `howlers-<UTC timestamp>.dump` (custom format) every 24 h and deletes dumps older than
  `BACKUP_KEEP_DAYS`. Copy `deploy/backups/` off the host (restic, rclone, borg, or your provider's snapshots) on its own
  schedule; a backup on the same disk isn't a backup. Restore into an empty database:
  `docker compose ... exec -T postgres pg_restore -U howlers -d howlers --clean --if-exists < howlers-….dump`.
  Test a restore at least monthly on a scratch host.
- **Uploads.** Mirror the bucket to another location with any S3 client from a cron job, e.g. the AWS CLI inside the
  compose network: `docker run --rm --network <project>_default -e AWS_ACCESS_KEY_ID=$S3_ACCESS_KEY
  -e AWS_SECRET_ACCESS_KEY=$S3_SECRET_KEY -e AWS_DEFAULT_REGION=us-east-1 -v /backups/uploads:/backup
  amazon/aws-cli:2.42.1 --endpoint-url http://s3:8333 s3 sync s3://howlers /backup`. Database and bucket backups should be taken close together: a row without its object
  shows a broken image, an object without its row is just unused space.
- **Secrets.** Keep `deploy/.env` in your password manager. Without `JWT_SECRET` existing sessions end (people sign in
  again); without `FILE_VISITOR_SALT` access-log hashes from before can't be linked to new ones (no other effect).

## 5. Migrations and upgrades

- **Flyway on boot.** The backend applies pending migrations (`backend/src/main/resources/db/migration/V*.sql`) at
  startup, inside a transaction per migration, before it reports ready. Applied migrations are never edited.
- **Backward-compatible (expand/contract).** Each release's migrations must work with the previous release's code, so a
  rollback is just "run the previous image": add nullable columns or columns with defaults, new tables and indexes;
  never rename or drop in the same release that stops using something. Drop columns one release later. Large index
  builds should use `CREATE INDEX CONCURRENTLY` in their own migration, with a `V<n>__name.sql.conf` file next to it
  containing `executeInTransaction=false`.
- **Upgrade steps.** Take a fresh dump (`docker compose ... exec pgbackup sh -c 'pg_dump -Fc -f /backups/pre-upgrade.dump'`),
  then `git pull && docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build backend web`.
- **Rollback.** Redeploy the previous image tag (`HOWLERS_BACKEND_IMAGE=…:previous`). Because migrations are additive, the
  old code runs against the new schema. Flyway won't downgrade; if a migration itself was wrong, restore the
  pre-upgrade dump (accepting the data written since) or ship a forward fix.

## 6. Zero-downtime notes

With one backend container, `up -d` restarts it and requests fail for the ~20–40 s the JVM needs to boot. To avoid that:
1. Run two backend replicas (`deploy.replicas: 2` or a second service) behind nginx (`upstream` with both), and
   restart them one at a time; `/readyz` turns ready only after migrations and startup, and nginx/Caddy retry on the
   other instance.
2. Before doing so, apply the scaling items below: WebSocket broadcasts and presence are per instance today.
3. Expand/contract migrations (above) make it safe for old and new code to run side by side during the roll.
Graceful shutdown is on by default in Spring Boot 3: in-flight requests finish (up to 30 s) before the JVM exits;
WebSocket clients reconnect with backoff and refetch.

## 7. Scaling beyond one node

- **Real-time (D-017).** Switch `enableSimpleBroker` to `enableStompBrokerRelay` in `WebSocketConfig` and run RabbitMQ
  with the STOMP plugin, so a message published on node A reaches a socket on node B. Sticky sessions aren't required
  for STOMP over plain WebSocket, but they reduce reconnect churn.
- **Presence.** Move `PresenceTracker`'s per-user session counts to Redis (`INCR`/`DECR` with a per-node heartbeat key, so a
  crashed node's sessions expire).
- **Account-status cache** is per node (≤ 30 s delay when suspending, D-021); fine, or replace with Redis pub/sub.
- **Feed cache and rate limits** already live in Redis and work across nodes.
- **Database.** Raise `DATABASE_POOL_SIZE` carefully (total connections across nodes < `max_connections`), add a
  PgBouncer in transaction mode if you run many nodes, and move to a managed PostgreSQL with point-in-time recovery
  when the dump-based backup isn't enough.
- **Storage** is already shared (S3 API: SeaweedFS here, or a managed bucket). For heavy media traffic, presigned GET redirects for podcast audio would cut
  API bandwidth (see D-023); that's a change in `MediaController`, not built.
- **Jobs.** The moderator-recognition recompute takes a PostgreSQL advisory lock and funding cycles lock their rows,
  so they're safe on several nodes. Review the other `@Scheduled` jobs (reputation queue, live counters) before scaling
  out.

## 8. Health checks and monitoring

- **Probes.** `GET /livez` and `GET /readyz` on port 8080 (used by the backend image's `HEALTHCHECK`). nginx answers
  `GET /healthz`. Readiness includes the database and storage (`StorageHealthIndicator`: bucket reachable or
  upload directory writable); Redis is left out on purpose, since rate limits and the feed cache degrade without it.
- **Metrics.** `GET /actuator/prometheus` on the management port 8081 (internal only). Start Prometheus and Grafana with
  `--profile monitoring`; Grafana provisions the Prometheus data source and the **Howlers overview** dashboard
  (`deploy/grafana/dashboards/howlers-overview.json`). To reach Grafana, uncomment the `grafana.` block in the Caddyfile
  (it has its own login) or use an SSH tunnel (`ssh -L 3000:localhost:3000` after publishing it on 127.0.0.1).
- **Suggested alerts.** `up{job="howlers-backend"} == 0` for 2 min; 5xx rate > 2 % for 10 min; HTTP p95 > 1 s for 15 min;
  `hikaricp_connections_pending > 0` for 5 min; a spike in `howlers_spam_flags_total`; disk usage of the host > 80 %; the
  newest file in `deploy/backups` older than 26 h.
- **Logs.** The backend logs one JSON object per line (ECS) with `traceId`; every API response carries `X-Trace-Id`, so a
  user's error report can be matched to its log lines. Ship container logs with Docker's `local` driver plus
  Vector/Promtail/Fluent Bit if you want search.

## 9. Security checklist

- Only ports 80/443 open (`ufw allow 80,443/tcp && ufw allow 443/udp` plus SSH). Postgres, Redis, the S3 service and 8081 stay
  internal.
- `deploy/.env` is `chmod 600` and not in git.
- Prod profile: secure cookies, HSTS, no Swagger exposure beyond the read-only docs (remove the Swagger location from
  `frontend/nginx.conf` if you prefer), seed off, prod safety check on.
- The SPA's CSP (in `frontend/nginx.conf`) allows scripts only from the same origin, WebSockets to the same host, and
  inline styles (Recharts/Radix); see D-026.
- Keep images patched: rebuild monthly (`docker compose ... build --pull`).

## 10. Stripe payments for moderator funding (D-034)
Test mode first. Nothing here moves real money until you deliberately switch to a live key and set
`FUNDING_LIVE_MODE=true` and `FUNDING_COMPLIANCE_ACK` after the compliance review in D-022/D-034.

1. **Keys.** In the Stripe dashboard (test mode on), open Developers → API keys and copy the **secret** key (`sk_test_…`).
   No publishable key is needed: Checkout is hosted by Stripe. Put the keys in the git-ignored files, never in the repo:
   - local backend (`./mvnw spring-boot:run`): `backend/.env.local` with `FUNDING_PROVIDER=stripe`, `STRIPE_SECRET_KEY=sk_test_…`
     and optionally `STRIPE_WEBHOOK_SECRET=whsec_…`;
   - Docker: the same variables in `.env` (local stack) or `deploy/.env` (production). Both compose files pass them through.
2. **Webhook endpoint (deployed).** Developers → Webhooks → Add endpoint: `https://<your domain>/api/v1/funding/webhooks/stripe`,
   with the events `checkout.session.completed`, `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`,
   `checkout.session.expired`, `invoice.paid`, `invoice.payment_failed`, `customer.subscription.deleted`, `charge.refunded`.
   Copy its signing secret into `STRIPE_WEBHOOK_SECRET`.
3. **Local webhooks (optional).** Contributions are confirmed without webhooks when you come back from Checkout (the
   server asks Stripe). For renewals, refunds and cancellations made in the dashboard, forward events with the Stripe CLI:
   `stripe listen --forward-to localhost:8080/api/v1/funding/webhooks/stripe` and use the `whsec_…` it prints as
   `STRIPE_WEBHOOK_SECRET`. `stripe trigger checkout.session.completed` sends a sample event; it will be acknowledged
   as "unknown checkout session" because it doesn't belong to a pending contribution.
4. **Try it.** Restart the backend; the funding banner says "TEST MODE: no real money". Create a pool, contribute, pay with
   the test card `4242 4242 4242 4242` (any future date, any CVC), and you land back on the pool page, which confirms the payment.
5. **Owner's live check (optional).** From `backend/`: `./mvnw test -Dtest=StripeLiveCheckTest -Dstripe.live=true
   -Dsurefire.excludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false`. It reads `STRIPE_SECRET_KEY` from
   `backend/.env.local` (never printed), refuses anything but `sk_test_`, creates a 5.00 USD Checkout Session and prints only
   the session id prefix and its URL.

Pools remember the provider and mode they were created with: demo pools keep their demo labels and stop taking contributions
after you switch to Stripe; create new pools for Stripe. The demo seed doesn't create a funding pool when Stripe is active.

## 11. Antivirus (ClamAV) and privacy jobs (D-029, D-030)

- **Prod.** `deploy/docker-compose.prod.yml` runs `clamav` (`clamav/clamav:1.4.3`) on the internal network with
  signatures in the `clamav-db` volume. The image's freshclam keeps them up to date, and the health check is
  `clamdscan --ping 1`. The backend starts once clamd is healthy and runs with `ANTIVIRUS=clamav` and
  `ANTIVIRUS_REQUIRED=true`: while clamd is unreachable, uploads are refused instead of being accepted unscanned. Set
  `ANTIVIRUS_REQUIRED=false` to fail open (uploads are then labelled "Checked, not an antivirus scan").
- **Memory.** clamd holds the signature database in RAM: about 1 GB in steady state (972 MiB measured with 3.6 million
  signatures), with a short peak during reloads. Together with the JVM, plan for at least 4 GB on the host (6 GB is
  comfortable). The first start downloads about 300 MB of signatures and takes a few minutes, during which the
  backend waits (start period 300 s).
- **Limits.** `deploy/clamav/clamd.conf` is mounted over the image's config. It raises `StreamMaxLength`/`MaxFileSize`
  to 600 MB and `MaxScanSize` to 1200 MB, above the 500 MB upload limits. With clamd's defaults, files over 25 MB would
  be refused (fail closed), or silently skipped if only `MaxFileSize` were low. If you raise `MAX_FILE_MB` or
  `MAX_MEDIA_MB`, raise these too.
- **Local.** In `docker-compose.yml` the service sits behind a profile because it is heavy:
  `ANTIVIRUS=clamav docker compose --profile antivirus up --build`. Locally, scanning isn't required, so uploads made
  before clamd is ready fall back to structural checks.
- **Check it.** `GET /api/v1/admin/maintenance` (as an admin) shows the engine, whether it's required and clamd's version
  when reachable. The EICAR test string is the standard harmless way to see a rejection.
  `./mvnw test -Dtest=ClamAvLiveTest -Dclamav.live.port=<port>` runs the client against a real clamd (EICAR found, clean
  files OK, a 40 MiB file accepted thanks to the raised limits).
- **Variables.** `ANTIVIRUS` (`none`|`clamav`), `ANTIVIRUS_REQUIRED`, `CLAMAV_HOST`, `CLAMAV_PORT` (3310),
  `CLAMAV_CONNECT_TIMEOUT` (PT2S), `CLAMAV_READ_TIMEOUT` (PT120S).
- **Legacy DM images.** At startup the backend moves DM images sent before D-024 from `media/` to `private/`, only when
  something is pending (`MIGRATE_LEGACY_DM_MEDIA=true` by default). It is idempotent and safe on several nodes, and
  every run is recorded in `maintenance_runs`. Admins can rerun it with `POST /api/v1/admin/maintenance/legacy-dm-media`.
  Take a storage backup first if your bucket has no versioning: the public copies are deleted once the move commits.

### Missed migration on an existing database
If a database was upgraded while a lower-numbered migration was still unmerged (Flyway then reports "Detected resolved
migration not applied to database"), start the backend once with `FLYWAY_OUT_OF_ORDER=true` in `.env`
(`docker compose up -d backend`), then remove the setting. Only do this for migrations that are independent of the
later ones (new tables or columns), which is how Howlers migrations are written.

## 12. Link previews, sitemaps, the installable app and web push (D-039, D-040)
- **APP_PUBLIC_URL must be the public origin** (e.g. `https://howlers.example.com`): canonical links, `og:url`,
  `og:image`, robots.txt and sitemaps are built from it.
- **Crawler routing** lives in `frontend/nginx.conf`: a `map` on `$http_user_agent` sends link-preview bots and search
  crawlers that ask for an SPA route to `/api/v1/seo/page` (internal location `/__howlers_preview`, original URI in
  `X-Howlers-Path`); files (images, `/assets/`) are always served as files. To support another bot, add its UA token to
  the map. If you put a caching CDN in front of nginx, either bypass the cache for HTML or vary on `User-Agent`,
  otherwise a cached preview document could be served to browsers.
- **robots.txt and sitemaps** are proxied to the backend (`/robots.txt`, `/sitemap.xml`, `/sitemaps/*.xml`). Submit
  `https://<domain>/sitemap.xml` in Google Search Console / Bing Webmaster Tools. Sizes: `SITEMAP_PAGE_SIZE` (default
  10000, max 50000) and `SITEMAP_MAX_POSTS` (default 100000 newest public posts).
- **Service worker**: `/sw.js` is served with `Cache-Control: no-cache` so updates are picked up; users see a "new
  version" prompt. The worker never caches `/api/` or `/ws`.
- **Web push**: generate a VAPID key pair once and keep it (rotating it invalidates every subscription):
  ```bash
  cd backend && ./mvnw -q compile && java -cp target/classes app.howlers.push.VapidKeyGenerator
  # or: npx web-push generate-vapid-keys
  ```
  Put `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` and `VAPID_SUBJECT` (`mailto:` or `https:` contact) in `deploy/.env`.
  The private key is server-side only and never logged. The backend must reach the push services over HTTPS
  (`fcm.googleapis.com`, `*.push.services.mozilla.com`, `*.notify.windows.com`, `*.push.apple.com`; override with
  `PUSH_ALLOWED_HOSTS`). Push needs HTTPS for the site itself (Caddy provides it). On iOS/iPadOS 16.4+ push only works
  from the app added to the Home Screen.
## 13. Passkeys, two-factor authentication and sign-in alerts (D-041, D-042)

**`ACCOUNT_SECRETS_KEY`** (required in production): base64 of 32 random bytes, `openssl rand -base64 32`. It encrypts
two-factor (TOTP) secrets at rest. The prod profile refuses to start without it, or with a value that isn't 32 bytes.
Store it with your other secrets and keep it with your backups: a database restored without the same key can't read
anyone's authenticator enrolment, and those users would need 2FA reset (below). Outside prod a blank key is derived
from `JWT_SECRET` and the backend logs a warning.

**Passkeys: relying party id and origins.**
- `WEBAUTHN_RP_ID`: blank = the host of `APP_PUBLIC_URL` (`howlers.example.com` in production, `localhost` in
  development). Passkeys are bound to it by the browser and authenticator, so **choose it once**: changing it later
  makes every registered passkey unusable (users can still sign in with their password and add new ones). It may be a
  parent domain of the site (`example.com` for `howlers.example.com`), never another site.
- `WEBAUTHN_ORIGINS`: blank = the origin of `APP_PUBLIC_URL`. Comma-separated exact origins (`scheme://host[:port]`)
  when the app is served from more than one, e.g. `http://localhost:5173,http://localhost:8088` for a dev machine
  that uses both Vite and the Docker stack.
- Browsers only run passkey ceremonies in a secure context: HTTPS, or `http://localhost` in development. A LAN address
  over plain HTTP (`http://192.168.1.20:5173`) won't offer passkeys; the UI says so.
- The backend logs `Passkeys: relying party id …, origins […]` at startup; check it after a deploy.

**New-sign-in emails** go through the normal mail transport (`MAIL_MODE`). `NEW_SIGNIN_ALERTS=false` turns them off
server-wide; users can turn them off for themselves in Settings > Account & security.

**Resetting 2FA for a locked-out user** (lost authenticator and recovery codes; verify their identity first). There is
no admin screen for this yet:
```sql
UPDATE account_security SET totp_secret_enc = NULL, totp_enabled_at = NULL, totp_pending_enc = NULL,
  totp_pending_at = NULL, totp_last_step = NULL, mfa_failed_attempts = 0, mfa_locked_until = NULL
  WHERE user_id = (SELECT id FROM users WHERE username = 'their_name');
DELETE FROM totp_recovery_codes WHERE user_id = (SELECT id FROM users WHERE username = 'their_name');
```
## 14. Stripe webhooks and Connect payouts to moderators (D-046, D-047)
Test mode only unless you deliberately go live. With Connect on, a closed Stripe pool pays each moderator's share as a Stripe
**Transfer** from the platform balance to the moderator's **Express** connected account. A moderator without a ready account
has their share held ("Held: connect your account") until they finish onboarding. Howlers stores only the account id and
Stripe's status flags; Stripe's hosted onboarding collects identity and bank details. **Admin → Payments** shows the mode,
which secrets are set (never their values), the last webhook received, Connect on/off, and the exact endpoint and events.

1. **Activate Connect (owner, once).** Stripe dashboard, test mode on → **Connect** → Get started. Choose the platform /
   marketplace model with **Express** accounts. Until this is done Stripe refuses to create accounts with "You can only create
   new accounts if you've signed up for Connect", and Howlers shows that as "Stripe Connect isn't activated on the platform's
   Stripe account yet".
2. **Turn it on in Howlers.** Set `STRIPE_CONNECT_ENABLED=true` (with `FUNDING_PROVIDER=stripe` and a `sk_test_` key) in
   `backend/.env.local`, `.env` or `deploy/.env`. Optional: `STRIPE_CONNECT_COUNTRY` (default `US`). The server refuses to start
   with Connect on and any other provider. With a live key it also needs `STRIPE_CONNECT_LIVE=true`, on top of
   `FUNDING_LIVE_MODE=true` and `FUNDING_COMPLIANCE_ACK` (D-034); with a test key `STRIPE_CONNECT_LIVE=true` is refused.
3. **Webhook endpoints in the dashboard (deployed).** Developers → Webhooks → **Add endpoint**, URL
   `https://<your domain>/api/v1/funding/webhooks/stripe` (the admin panel shows the URL it expects).
   - **"Events on your account"** endpoint: `checkout.session.completed`, `checkout.session.async_payment_succeeded`,
     `checkout.session.async_payment_failed`, `checkout.session.expired`, `invoice.paid`, `invoice.payment_failed`,
     `customer.subscription.deleted`, `charge.refunded`, `transfer.created`, `transfer.updated`, `transfer.reversed`.
     Its signing secret goes in `STRIPE_WEBHOOK_SECRET`.
   - **"Events on connected accounts"** endpoint (same URL): `account.updated`, `payout.paid`, `payout.failed`,
     `payout.canceled`. Stripe gives it its own signing secret: put it in `STRIPE_CONNECT_WEBHOOK_SECRET`. A signature valid
     for either secret is accepted; event ids are de-duplicated exactly as for Checkout events.
4. **Local development with the Stripe CLI.** `stripe login`, then
   `stripe listen --forward-to localhost:8080/api/v1/funding/webhooks/stripe --forward-connect-to localhost:8080/api/v1/funding/webhooks/stripe`
   and use the one `whsec_…` it prints as `STRIPE_WEBHOOK_SECRET` (it signs both kinds; leave
   `STRIPE_CONNECT_WEBHOOK_SECRET` empty). Webhooks are optional locally: returning from onboarding re-reads the account
   from Stripe, and the funding job re-reads accounts that aren't enabled yet every 10 minutes.
5. **Try it.** As a moderator, open **Moderator funding → See your payouts** (`/funding/payouts`) and press **Connect with
   Stripe**. Stripe's test onboarding accepts test data (phone `000 000 0000`, SMS code `000000`, SSN `000-00-0000`, the test
   bank account it offers). Back on Howlers the account shows "Payouts enabled". Close a pool: the share shows
   "Paid out (test mode)" and the transparency report adds a PAYOUT ledger row and "allocated = paid out + held".
6. **Platform balance in test mode.** Transfers need *available* balance. Card payments take days to become available, so a
   fresh sandbox refuses transfers with `balance_insufficient`; Howlers marks the payout "Held: transfer failed, retrying"
   and retries with backoff (an admin review is raised after 3 failures). Pay a test contribution with
   `4000 0000 0000 0077` (funds available immediately) before closing the pool, or use **Run payouts now** afterwards.
7. **What needs a person.** Refunds after a payout are never clawed back automatically, and reversed transfers are never
   re-sent automatically: both appear under **Admin → Payments → Payout reviews**, where an admin records what was done.
8. **Owner's live check (optional).** From `backend/`:
   `./mvnw test -Dtest=StripeConnectLiveCheckTest -Dstripe.live=true -Dsurefire.excludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false`
   (add `-Dstripe.envFile=<path to .env.local>` if it isn't `backend/.env.local`). It refuses anything but a test key, creates
   one Express account and one onboarding link, prints only the account id prefix and whether the link was created, deletes
   the account again, and never creates a transfer.
