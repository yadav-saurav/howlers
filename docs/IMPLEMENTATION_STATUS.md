# Implementation status

_Last updated: 2026-11-24 (Phases 0–2, layout pass, Phase 3 moderation core, Phases 4–6; Phase 10 e2e suite and accessibility gate; privacy and safety hardening D-028 to D-030)._

## Implemented and verified
| Feature | Backend | Frontend | Verified by |
|---|---|---|---|
| Register / login / logout | ✅ | ✅ | ApiSecurityIT, API smoke run, browser |
| Refresh rotation + reuse detection, Origin check | ✅ | ✅ single-flight refresh | ApiSecurityIT, apiClient tests |
| Email verification / password reset (log-mode mail) | ✅ | ✅ | API smoke run |
| Transactional email via Brevo API or SMTP (D-038): after-commit async sending, retries on 429/5xx, HTML + text templates, verify page states (verified / already verified / expired / invalid) with resend, per-address forgot-password limit | ✅ | ✅ | AuthEmailIT (6), BrevoMailTransportTest (12, local stub server), MailDispatcherTest (10), EmailTemplatesTest (6), verifyOutcome.test.ts (3); live send not run (backend/.env.local had no BREVO_API_KEY, MAIL_FROM_ADDRESS or MAIL_TEST_TO); not verified in a browser |
| Sessions list / revoke | ✅ | ✅ | — |
| Profile, avatar/banner, settings, privacy, deactivate, delete (anonymise) | ✅ | ✅ | API smoke run |
| Onboarding (topics → communities → people → theme) | ✅ | ✅ | API smoke run |
| Communities: create, visibility, rules, flairs, icon/banner, join/leave, members, roles | ✅ | ✅ | ApiSecurityIT (private isolation, role escalation) |
| Posts: text/link/image/poll, sanitised HTML, tags, flair, edit, delete, duplicate guard | ✅ | ✅ | TextSafetyTest, API smoke run |
| Voting + karma (posts and comments), concurrency-safe | ✅ | ✅ optimistic | VoteConcurrencyIT, ScoreMathTest |
| Nested comments: sort, lazy replies, edit/delete, mentions | ✅ | ✅ | API smoke run, browser |
| Feeds: For You, Following, Latest, Popular, Rising, Top(t); community sorts + pins | ✅ | ✅ infinite scroll | API smoke run, browser |
| Search (posts/communities/users) + suggestions | ✅ | ✅ | API smoke run |
| Saved / hidden posts | ✅ | ✅ | API smoke run |
| Notifications (reply, mention, follower, milestone, role; grouped) | ✅ | ✅ live badge over WebSocket, 60 s poll only while disconnected | API smoke run, RealtimeIT |
| Rate limiting (Redis, in-memory fallback) | ✅ | — | — |
| Health (db, redis, storage), trace ids, OpenAPI | ✅ | — | manual |
| Demo seed (12 users, 10 communities, threads, real vote rows) | ✅ | — | startup |
| Familiar layout: card/compact views, sort menu, pill actions, collapsible nav (Recent/Communities), recent-posts rail, community banner, avatar thread lines, OP badge | — | ✅ | browser (desktop + 375px) |
| Context Mode: stance groups, evidence, open questions, agreement/disagreement, discussion map, reader relabelling | ✅ | ✅ | KnowledgeLayerIT, TextSignalsTest, browser |
| Thread Digest: 3 lengths × 5 focuses, attributed quotes, fact/opinion/anecdote/speculation tags, honest consensus, cache + staleness + refresh | ✅ | ✅ | KnowledgeLayerIT, browser |
| LLM digest + AI stance suggestions: validated citations/quotes, token budget + coverage, extractive fallback with notice, per-user limits, token usage log | ✅ | ✅ | InsightLlmIT, DigestValidatorTest, LlmDigestEngineTest, CommentSelectorTest, StanceValidationTest, engine.test.ts, one live run |
| Community AI: chat with history, community/thread/date scope, permission-filtered retrieval, numbered citations validated server-side, source cards, follow-ups, copy, feedback; AI model via official SDK or offline fallback | ✅ | ✅ | CommunityAiIT, citations.test.ts, browser (offline mode and the live hosted model: ~7–9 s per answer, ~0.8–1.5k input / ~0.5k output tokens, all citations valid) |
| Moderation: reports (dedup per reporter), grouped mod queue, remove/approve/lock/pin (≤3 pins), bans with expiry and rank rules, appeals that reverse actions, append-only audit log, mod stats, affected-user page, user blocking | ✅ | ✅ | ModerationAndPulseIT, live API smoke (23 checks), browser |
| Community Pulse: polls, predictions, opinion and debate prompts; results hidden until you vote; change-vote option; anonymous reasoning; close, resolve with accuracy, history; home and community rails | ✅ | ✅ | ModerationAndPulseIT, live API smoke, browser |
| Podcasts: discovery (featured, continue listening, recent, saved, categories, history), episode page with chapters, searchable synced transcript and sources, global mini player that keeps playing across pages, resume, speed, queue, Media Session; studio with upload (magic-byte checked, range-served), VTT/SRT/timestamped transcripts, chapters, sources, review → publish workflow; AI model script drafts with validated citations | ✅ | ✅ | PodcastIT, TranscriptParserTest, live run with a real 43 s two-voice MP3 and a real AI model draft, browser |
| Direct messages: get-or-create 1:1 conversations, text + up to 4 own images, history paging, unread counts, read receipts ("Seen"), typing, presence, soft delete, search, `dmPolicy` (everyone / people I follow / nobody, reply always allowed), blocks in both directions | ✅ | ✅ | DirectMessagesIT (4), RealtimeIT, browser: two users chatting live (message, unread badge, reply, "Seen" and incoming reply without reload) |
| Real-time channel: STOMP over WebSocket at `/ws`, token checked on CONNECT, permission-checked SUBSCRIBE, events sent after commit; live Pulse and mod-queue refresh | ✅ | ✅ auto-reconnect with backoff | RealtimeIT (live delivery, typing, bad token rejected, foreign mod queue refused) |
| Live feeds and threads: "N new posts" pill on home and community feeds (never auto-inserts), new comments inserted live on post pages, post and comment scores batched every ~2 s; private communities only on members' topics | ✅ | ✅ | RealtimeIT (new post/comment/score delivery, private isolation), live/logic.test.ts, tree.test.ts, browser: pill appeared and loaded a post made by another user; open post page showed a new comment (highlighted) and score 0→2 without reload |
| Moderator funding (**demo mode, no real money**): pools with purpose/goal/period, one-time and monthly contributions (recurring job, cancel), owner-set allocation policy (equal among active mods, activity-weighted from resolved reports and appeals with caps, manual with a required note), period close job, append-only double-entry ledger, public transparency report that reconciles, anonymous-by-default contributors, `PaymentProvider` with HMAC-signed, replay-protected demo webhooks; a non-demo provider refuses to start | ✅ | ✅ | FundingIT (6), FundingUnitTest (7), money.test.ts; not verified in a browser |
| Private files: upload (streamed, magic-byte typed, executables/scripts/macro formats blocked, zip bomb and embedded-executable heuristics), per-user quota with usage meter, name search, rename, soft delete with storage cleanup, owner downloads with Range, attachment-only serving with nosniff and sandbox CSP | ✅ | ✅ | FilesIT (4), DefaultFileScannerTest (4) |
| Share links: expiry, bcrypt password with per-visitor lockout, download limit (atomic), revocation, signed 5-minute download grants, access log with signed-in viewer or daily salted visitor hash (no IPs), public `/s/:token` page working signed out | ✅ | ✅ | FilesIT |
| End-to-end encrypted files: in-browser AES-256-GCM chunked container with encrypted name/type, key in URL fragment or PBKDF2-SHA256 (600k) wrapped passphrase, server stores ciphertext only (UNSCANNED), recovery key, optional per-device key memory, threat model page | ✅ | ✅ | crypto.test.ts (11), FilesIT |
| Topic reputation v1: per-topic points from votes (community topic + topic tags), quality-weighted by voter expertise, self-votes ignored, young/low-karma voters discounted, per-pair and per-topic daily caps, removal reversal; queued on vote, recomputed by a job, deterministic nightly/startup rebuild; profile badges, `/topics/:slug/leaders`, thread expertise chips | ✅ | ✅ | ReputationAndRecognitionIT, ReputationMathTest, reputation/logic.test.ts |
| Moderator recognition: monthly quality-weighted score per community (consistency, response time, appeal outcomes, community health, peer endorsements; never removal counts), transparent formula and per-row breakdown, badges, global and per-community leaderboard, profile section, peer "+1 helpful", ranking opt-out | ✅ | ✅ | ReputationAndRecognitionIT, ModeratorScoreMathTest, reputation/logic.test.ts |
| Admin dashboard: overview with real counts and a 14-day sign-up chart, user search/detail, suspend/unsuspend with reason (sessions revoked, live tokens cut on the next request), promote/demote (never yourself), community quarantine/ban/restore with banner and moderator notification, site-wide spam flags and reports, append-only audit log; demo admin `admin_demo` | ✅ | ✅ | AdminIT (3), helpers.test.ts |
| Mute (private): users (feeds, collapsed comments, no notifications) and communities (home feeds); Settings → Muted | ✅ | ✅ | TrustAndSafetyIT, mutes.test.ts |
| Spam heuristics at write time (link velocity, duplicates across communities, new-account bursts, excessive mentions): hold for review or reject, flags in the mod queue ("Held" tab) and admin Flags; approve releases with deferred notifications; thresholds in `howlers.spam.*` | ✅ | ✅ | TrustAndSafetyIT (each heuristic, hold/approve/remove), SpamSignalsTest |
| Feed rows carry `viewer.canModerate` (one batched query per page); mod actions in feed card menus | ✅ | ✅ | TrustAndSafetyIT |
| Onboarding skip persists | ✅ | ✅ | TrustAndSafetyIT |
| Smart Collections: private by default, notes, tags, search, type filter, move, reorder (drag + arrows), share link / revoke, shared view | ✅ | ✅ | KnowledgeLayerIT, tags.test.ts, browser |
| S3/MinIO storage driver (`STORAGE_DRIVER=s3`): private bucket (no ACLs, reads proxied), streamed uploads with 8 MB multipart parts and abort on failure, ranged reads turned into S3 Range GETs, zip scan staging, MinIO + bucket init in docker-compose (D-023) | ✅ | — | S3StorageServiceTest (6, in-memory S3Client + Spring's Range converter); **not run against a real MinIO** |
| Image variants: 320/960 px PNG/JPEG copies without metadata, EXIF orientation applied, GIF/WebP original only; `thumbnails` in media DTOs, `?w=` on `/files/**`; `srcset`/`sizes`, lazy loading and async decoding in post cards, rows, post page, avatars/icons, rail and collection thumbnails (D-024) | ✅ | ✅ | ImageVariantsTest (3), ImageVariantsIT, images.test.ts |
| Private DM images: `/me/dm-media` uploads under `private/`, per-reader signed URLs re-checked on every request (participant, message not deleted), per-recipient realtime payloads, refetch on expiry; DM images can't go into posts (D-024) | ✅ | ✅ | DmMediaIT |
| Redis first-page feed id cache (Popular/Latest/Rising/Top, public community feeds), per-viewer filter + hydration, 30 s TTL, step-aside when Redis is down (D-025) | ✅ | — | FeedCacheIT (3) |
| Observability: Prometheus registry, custom metrics (votes, posts, comments, DMs, WebSocket sessions, AI latency/tokens, spam flags, feed cache), management port 8081 in prod, `/livez` `/readyz`, ECS JSON logs with traceId, Grafana dashboard (D-026) | ✅ | — | DemoSeedIT (scrapes `/actuator/prometheus`); dashboard JSON validated, not loaded into Grafana |
| Production guard rails: prod refuses dev JWT/webhook secrets, weak DB password, missing visitor salt, insecure cookies, default S3 secret, public metrics; HSTS; CSP checked (`ws(s)://$host`, inline styles for Recharts); `docs/DEPLOYMENT.md`, `deploy/docker-compose.prod.yml`, `deploy/Caddyfile`, Prometheus + Grafana provisioning | ✅ | — | ProductionSafetyCheckTest (3); compose/Caddy/Grafana files YAML/JSON-validated only (**not executed**) |
| Richer demo seed: moderator teams in 3 communities with reports, removals, approvals and two appeals (one overturned) written through the moderation service, recomputed leaderboard, two shared collections, a demo funding pool with 5 demo contributions; idempotent, `howlers.seed.activity`; dismissible "Demo data" notice from `GET /demo/status` | ✅ | ✅ | DemoSeedIT (own embedded PostgreSQL), demo api.test.ts |
| Wolf-pup mascots in empty states, 404/route errors, offline and maintenance errors and the full-page loader; theme-aware colours, reduced-motion aware idle (D-048) | — | ✅ | apiError.test.ts (3); screenshots in both themes; axe audit (`08-accessibility`) |
| Brand assets: GitHub social preview (1280×640), 512 px logo, wolf SVG, README screenshots, regenerated by `docs/brand/render.sh` (D-048) | — | — | Rendered with headless Chrome; the social preview still has to be uploaded in GitHub settings by the owner |
| Accessibility breadth: axe audit extended to share page, funding, pool report, podcast studio, leaderboard/admin/mod tabs, verify-email states, 404; keyboard tests for menus, search, mobile drawer, skip link | — | ✅ | `08-accessibility`, `09-keyboard`; no manual screen-reader pass (see docs/ACCESSIBILITY.md) |
| k6 load test of the Docker stack (anonymous browse/search, signed-in vote/comment mix), results and bottlenecks (D-048) | — | — | `loadtest/`, docs/PERFORMANCE.md |

## Tests (latest run)
- Privacy hardening (2026-11-24): backend `./mvnw test` **145 passed, 0 failed, 1 skipped** (ClamAvLiveTest, which runs only with `-Dclamav.live.port`; it passed against real clamd in Docker). Added ImageMetadataTest 8, ClamAvClientTest 9, AntivirusUploadIT 3, PrivacyHardeningIT 3, SessionRevocationIT 2, ClamAvLiveTest 1. Frontend: `npm run lint` (tsc + ESLint) clean, `npx vitest run` **142 passed** across 21 files, `npm run build` passes.
- Backend `./mvnw test`: **120 passed, 0 failed** (Phase 10 added ProductionSafetyCheckTest 3, FeedCacheIT 3, DmMediaIT 1,
  DemoSeedIT 1, ImageVariantsIT 1, ImageVariantsTest 3, S3StorageServiceTest 6; previously: AdminIT 3, CommunityAiIT 4, ApiSecurityIT 6, TextSafetyTest 3, DefaultFileScannerTest 4, FilesIT 4, FundingIT 6, FundingUnitTest 7, KnowledgeLayerIT 4, TextSignalsTest 3, DirectMessagesIT 4, RealtimeIT 5, ModerationAndPulseIT 4, PodcastIT 3, TranscriptParserTest 5, ModeratorScoreMathTest 5, ReputationAndRecognitionIT 4, ReputationMathTest 8, SpamSignalsTest 3, TrustAndSafetyIT 10, ScoreMathTest 6, VoteConcurrencyIT 1).
- Frontend `npx vitest run`: **132 passed** across 20 files (Phase 10 added images.test.ts and demo/api.test.ts); `npm run build` passes with zero type errors.
| Playwright end-to-end suite (D-027): the 23 owner flows against the real stack, Chromium, repeatable on one database; `npm run e2e`; CI job with PostgreSQL/Redis services, seeded backend jar, offline AI, `vite preview` | — | ✅ | 37 tests passing against the local dev stack (5174 → 8080) |
| Accessibility gate (D-027): axe-core on 10 pages × 2 themes × desktop/375 px, keyboard checks (skip link, focus ring, dialog trap and focus return); contrast tokens, labels, roles, target sizes fixed | — | ✅ | 08-accessibility (0 serious/critical), 09-keyboard; docs/ACCESSIBILITY.md |
| Affected user's moderation page never names the individual moderator (D-014/D-027) | ✅ | ✅ | ModerationAndPulseIT, e2e flow 22 (frontend side; the backend change isn't deployed on the shared dev server) |
| Image originals stripped of EXIF/GPS/XMP/IPTC/comments/text chunks/trailing data, losslessly (JPEG segments, PNG chunk whitelist, GIF extensions, WebP chunks); orientation kept as an orientation-only EXIF; ICC kept; malformed containers refused; private files byte-identical (D-028) | ✅ | — | ImageMetadataTest (8, spec-built fixtures with a GPS IFD), PrivacyHardeningIT |
| ClamAV scanning (`howlers.files.antivirus=clamav`): INSTREAM client with timeouts, structural checks first, private files + podcast media + images, fail open/closed (`antivirus-required`, true in prod), `scanEngine` + honest "Scanned by ClamAV" vs "Checked, not an antivirus scan" labels; `clamav` service in both compose files (profile `antivirus` locally) with raised size limits (D-029) | ✅ | ✅ | ClamAvClientTest (9, fake clamd), AntivirusUploadIT (3), ClamAvLiveTest against real `clamav/clamav:1.4.3` in Docker (EICAR found, clean and 40 MiB files OK), helpers.test.ts |
| Legacy public DM images moved to `private/` by an idempotent, recorded job (startup + `POST /admin/maintenance/legacy-dm-media`); post images and publicly linked keys untouched; old URL 404; send path privatizes public DM-only uploads (D-030) | ✅ | — | PrivacyHardeningIT (3) |
| Suspension, deactivation, deletion and role change close the user's WebSockets at once (1008); STOMP CONNECT requires an ACTIVE account and takes the role from it (D-030) | ✅ | — | SessionRevocationIT (2, real STOMP clients) |
| Streaming E2EE decryption to disk (`showSaveFilePicker`) for files ≥ 64 MB, in-memory fallback with a > 500 MB warning; container format unchanged (D-030) | — | ✅ | streamDecrypt.test.ts (6); **not exercised in a browser** (no dev server in this task) |
| ESLint flat config (typescript-eslint, react-hooks, jsx-a11y) in `npm run lint`; findings fixed or justified inline | — | ✅ | `npm run lint` clean |

## Tests (latest run)
- Backend `./mvnw test`: **102 passed, 0 failed** (AdminIT 3, CommunityAiIT 4, ApiSecurityIT 6, TextSafetyTest 3, DefaultFileScannerTest 4, FilesIT 4, FundingIT 6, FundingUnitTest 7, KnowledgeLayerIT 4, TextSignalsTest 3, DirectMessagesIT 4, RealtimeIT 5, ModerationAndPulseIT 4, PodcastIT 3, TranscriptParserTest 5, ModeratorScoreMathTest 5, ReputationAndRecognitionIT 4, ReputationMathTest 8, SpamSignalsTest 3, TrustAndSafetyIT 10, ScoreMathTest 6, VoteConcurrencyIT 1).
- Frontend `npx vitest run`: **126 passed** across 18 files; `npm run build` passes with zero type errors.
- Playwright `npm run e2e` (D-027): **37 passed** (23 flows + the E2EE round trip, 10 axe page audits × 4 theme/viewport
  combinations, 3 keyboard checks) against the local dev stack, 2026-11-23. The CI `e2e` job hasn't run yet.
- API smoke script (47 checks across auth, communities, posts, polls, votes, comments, feeds, search,
  notifications, permissions): **47/47 after fixing the Rising-feed SQL bug it found**.

## Known gaps / not yet verified
- `deploy/docker-compose.prod.yml`, the Caddyfile, Prometheus and Grafana were validated and reviewed but not run (no public domain/TLS on the dev machine).
- Email delivery (D-038): `MAIL_MODE=log` (default, links in the backend log), `smtp`, or `brevo` (Brevo's REST API).
  The Brevo client is tested against a local stub server; whether a live send was run is recorded in the D-038 row
  above. Brevo click tracking can't be turned off per message through the API (account setting or Enterprise only).
- Digest and Context Mode use the configured LLM when `ANTHROPIC_API_KEY` is set (D-031 to D-033): cited, validated digests
  and AI-suggested stances for unlabelled comments, with the extractive engine and rule-based stances as the stated
  fallback. Without a key (and in tests) they stay offline, and on the seeded threads most comments are then "neutral"
  until readers label them. Verified with a fake model in tests and one manual live run; not checked in a browser.
- Not built yet: podcast video generation. Payouts to moderators run through Stripe Connect in test mode (D-046/D-047),
  off until the owner activates Connect in the Stripe dashboard and sets `STRIPE_CONNECT_ENABLED=true`. Moderator funding has a real
  provider (Stripe hosted Checkout, test mode by default, D-034/D-035); live mode needs an explicit compliance
  acknowledgement.
- Podcast audio generation (ElevenLabs TTS, D-036/D-037) runs only when `ELEVENLABS_API_KEY` is set. No RSS feed
  exists yet to carry the AI-voice label.
- External services in tests: the suite never calls Stripe, ElevenLabs or Brevo (fake gateways and local stub servers).
  Live checks are opt-in tests tagged `live`/`stripe-live`; whether each one has been run is noted in its row above.
- Docker: `docker compose up --build` runs locally (Docker Desktop 29.9). Verified: all five services healthy (PostgreSQL, Redis, SeaweedFS S3, backend, web), demo login, feeds, AI model AI and the WebSocket through nginx on http://localhost:8088; uploads go to the private bucket (image with 320/960 variants, private file with a ranged 206 read, owner-only access, delete removes the object, anonymous bucket access 403). MinIO no longer publishes public images, so the bundled S3 service is SeaweedFS.
- Accessibility: no manual screen-reader pass yet; moderate/minor axe findings not triaged; pages outside the audited ten
  not scanned (see docs/ACCESSIBILITY.md).
- Real-time broker and presence are in-memory, per instance (D-017): fine for one backend node, not for several.
- DM images are private, including ones sent before D-024 (moved by the D-030 job), except a legacy image whose URL was
  also published in a post, comment or profile (kept public, counted as skipped). Messages are not end-to-end encrypted.
- WebP and GIF get no resized variants (their originals are metadata-stripped like the others, D-028).
- Feed first pages can be up to 30 s old in order (new posts); counts, votes and removals are always fresh (D-025).
- Files: antivirus scanning is optional (ClamAV, D-029) and off unless configured; without it, checks are structural and labelled so. Files aren't rescanned when signatures update. Legacy OLE Office files may carry macros. Streaming E2EE decryption (D-030) needs the File System Access API (Chromium desktop); other browsers still decrypt in memory and warn above 500 MB. The streaming save path was not exercised in a browser (unit tests and build only).
- Spam heuristics are rate and repetition rules only (no content judgement): see D-021 for what they don't catch. The
  account-status cache and the WebSocket close on suspension are per node (other nodes refuse the next CONNECT; REST
  within 30 s).
- Admin dashboard and Settings (with Muted) load and pass the axe audit in the e2e suite (`08-accessibility`, admin as
  `admin_demo`); suspend/mute/hold actions are covered by AdminIT and TrustAndSafetyIT but aren't clicked in a browser.
- The Playwright `e2e` CI job has run green on GitHub (CI run 45235872610 on `main`: backend, frontend, e2e and images
  jobs all successful).

## Stripe payments for moderator funding (D-034, D-035)
| Item | Backend | Frontend |
|---|---|---|
| `FUNDING_PROVIDER=stripe`: hosted Checkout (payment mode once, subscription mode monthly with inline `price_data`), pending contributions, confirm on return (server retrieves the session) and by webhook, `Stripe-Signature` verification, invoice cycles, failed renewals, provider cancellation, refunds as reversing ledger rows, mode (`demo`/`test`/`live`) on every money row, live keys refused without `FUNDING_LIVE_MODE` + `FUNDING_COMPLIANCE_ACK` | ✅ `StripeFundingIT` 9, `StripeProviderTest` 5, `FundingUnitTest`/`FundingIT` updated | ✅ redirect to Checkout, return banner polling the confirm endpoint (success / pending / cancelled / expired / failed), mode labels from the API, "Allocated, not paid out" |
| Payouts to moderators: built with Stripe Connect in test mode (see "Stripe Connect payouts" below). Still not built: automatic refund of payments that arrive after a pool closed | ✅ (D-046) / ❌ | ✅ / ❌ |
| Live test-mode check (`StripeLiveCheckTest`, excluded from the suite) | not run: no `STRIPE_SECRET_KEY` in `backend/.env.local` at the time of writing | |
- ElevenLabs live check run on 2026-11-24 against the owner's account with a TTS-only restricted key: Matilda (HOST A) 3.06 s / 821 ms and George (HOST B) 3.76 s / 436 ms, joined to 7.18 s. The original HOST A default (Sarah, `EXAVITQu4vr4xnAQ5fpe`) returned `voice_not_found` on this account, so the default is now Matilda.
- Brevo live check run on 2026-11-24 with the owner's restricted key (Transactional email only): Brevo accepted one verification-style email (message id returned). The sender is a Gmail address, which Brevo flags as not authenticated for sending through Brevo, so delivery may land in spam until a custom domain is verified.
- Stripe live test-mode check run on 2026-11-24 with the owner's sandbox key: `StripeLiveCheckTest` created a 5.00 USD Checkout Session (`cs_test_…`) and returned its hosted URL. No webhook secret is configured locally; payments are confirmed when the buyer returns from Checkout.

## Link previews, SEO, installable app and web push (D-039, D-040)
| Item | Backend | Frontend |
|---|---|---|
| Open Graph / Twitter preview documents for posts, communities, profiles, podcast episodes, landing and static pages; generic `noindex` fallback for private, removed, held, deleted, quarantined/banned and missing content | ✅ `SeoService`, `SeoController`; `SeoIT` 4 | ✅ nginx crawler routing (checked with `nginx -t` and curl against a fake backend in a throwaway container), default meta in `index.html` |
| OG image 1200×630 and app icons (192, 512, maskable 512, apple-touch 180, badge 72) | | ✅ rendered with headless Chromium by `npm run brand:images`, committed |
| robots.txt, sitemap index and paged sitemaps (communities, recent public posts, podcasts) | ✅ `SeoIT` | ✅ nginx + Vite dev/preview proxies |
| Canonical link per route, `document.title` per route | | ✅ `trackCanonical` (Vitest); titles existed |
| Web manifest, service worker (shell precache, cache-first `/assets/`, offline page, never `/api/`), update prompt | | ✅ `sw.test.ts` 7; checked in headless Chromium against `vite preview`: registers, precaches, controls the page, offline navigation shows the offline page, no `/api` entries cached |
| Web push: VAPID config, RFC 8291 encryption, subscriptions (V19) with 404/410 cleanup, pushes for replies, mentions, DMs, moderation (+ opt-in followers/activity), blocks/mutes/preferences, batching and rate limit, test push | ✅ `WebPushCryptoTest` 5 (RFC 8291 vector), `WebPushIT` 6 (local fake push service decrypts and verifies) | ✅ Settings › Preferences › Push notifications (per device on/off, categories, test, other devices), resync on boot, release on sign-out |
| Delivery through a real browser push service (FCM, Mozilla, Apple) | ⚠️ not tested: no VAPID keys are configured for the running backend and no real subscription was made | ⚠️ the Settings card was not exercised in a browser against a push-enabled backend |
| Per-post dynamic OG images | ❌ not built (optional) | |
## Passkeys, two-factor authentication and sign-in alerts (D-041, D-042)
| Item | Backend | Frontend |
|---|---|---|
| Passkeys: register (named, discoverable, user verification required), list, rename, remove; usernameless "Sign in with a passkey" issuing the normal session; single-use, 5-minute, cookie-bound challenges; counter regression refuses and flags | ✅ Yubico `webauthn-server-core` 2.10.1; `PasskeyIT` 7 (software EC P-256 authenticator: registration, sign-in, refresh, replay, foreign binding cookie, expired challenge, cross-user registration, wrong origin, missing UV, unknown credential, counter regression and flag, zero-counter authenticators, removal, suspended account) | ✅ settings card with support detection, login button, readable errors; `webauthn.test.ts` 7, `LoginSecondStep.test.tsx` (passkey button 3) |
| TOTP 2FA: QR (server-side SVG) + manual key, confirm, 10 recovery codes shown once (hashed), regenerate, disable with password + code; login second step with a 5-minute token; ±1 step skew; replay refused; 5 wrong codes lock for 5 min; secrets AES-GCM encrypted with `ACCOUNT_SECRETS_KEY` (prod refuses to start without it) | ✅ `TwoFactorIT` 7, `TotpTest` 13 (all RFC 6238 vectors), `AccountSecretsCipherTest` 2, `ProductionSafetyCheckTest` +1 | ✅ enable dialog (password, QR, code, codes with copy/download), regenerate, turn off, login code step with recovery-code mode; `LoginSecondStep.test.tsx` (3), `logic.test.ts` 4 |
| New-device sign-in email (device cookie + browser/OS family; not for the first known device; per-user opt-out, default on; only a keyed network-prefix hash stored) | ✅ `NewDeviceAlertIT` 4 (via the recorded mail transport), `UserAgentFamilyTest` 10 | ✅ "Sign-in alerts" switch |
| Sessions: sign-in method per session, "Sign out everywhere else" | ✅ `NewDeviceAlertIT` | ✅ badges + button |
| Signed-in `/auth/**` endpoints (sessions list, resend verification, revoke) were answering 401: the JWT filter skipped `/auth/` entirely; fixed (D-041) | ✅ covered by the ITs above | |
| Admin reset of a user's 2FA, passkey autofill (conditional mediation), attestation/AAGUID allow-lists | ❌ not built (manual SQL for the reset in DEPLOYMENT.md section 13) | ❌ |
| Real browser and real authenticator (platform passkey, phone, security key) and a real authenticator app | not verified: tests use a software authenticator and computed codes; no browser session was run | |
## Stripe Connect payouts to moderators (D-046, D-047)
| Item | Backend | Frontend |
|---|---|---|
| Express connected accounts: create via the API (`type=express`, `transfers` requested), single-use account links (refresh/return to `/funding/payouts`), status NOT_STARTED / PENDING / REQUIREMENTS_DUE / ENABLED from Stripe's flags (on return, `account.updated`, and a 10-minute sync job); only the account id and flags are stored; only moderators onboard, only themselves | ✅ `StripeConnectIT` (onboarding, permissions), `ConnectConfigTest` | ✅ `/funding/payouts`: account card (Connect with Stripe / Resume onboarding / Check status), return and expired-link handling |
| Payouts: one per positive allocation of a closed Stripe pool; a Transfer per payout (idempotency key per payout and attempt, transfer group lookup before retries), held until the account is ENABLED, released by `account.updated`, the return page and the job; failures retried with backoff; append-only PAYOUT / PAYOUT_REVERSAL ledger rows; reconciliation allocated = transferred + held | ✅ `StripeConnectIT` (transfer on close, held then released, refused and lost responses, webhook replays, reversal, reconciliation) | ✅ labels "Paid out (test mode)" and "Held: connect your account" in the transparency report and on the payouts page; PAYOUT rows in the ledger |
| Connect webhooks (`account.updated`, `transfer.created/updated/reversed`, `payout.paid/failed/canceled`) with the same signature check and event-id de-duplication; optional second signing secret for a connected-accounts endpoint | ✅ | n/a |
| Refunds after a payout flagged for manual review (no claw-back); reversed transfers never re-sent automatically; transfers failing 3 times flagged | ✅ | ✅ Admin → Payments → Payout reviews (resolve with a note, written to the audit log) |
| Admin "Stripe status" panel: mode, secrets set (never values), last webhook, Connect on/off, counts, endpoint URL and event lists | ✅ admins only | ✅ Admin → Payments |
| Live Connect | refused unless `STRIPE_CONNECT_LIVE=true` on top of D-034's live acknowledgement; not exercised | |
- Live test-mode check (`StripeConnectLiveCheckTest`, tag `live`, excluded) run on 2026-11-24 with the owner's sandbox key: Stripe
  refused the account creation with "You can only create new accounts if you've signed up for Connect, which you can do at
  https://dashboard.stripe.com/connect." (no error code). This is an owner step: activate Connect in the dashboard, then rerun
  the check. No account was created, so nothing had to be deleted. No transfer was attempted.
- Not verified in a browser.
## Pack rooms, reactions and automod (D-043 to D-045, V17)
| Item | Backend | Frontend | Verified by |
|---|---|---|---|
| Pack rooms: 3–50 members, name + icon emoji, owner/admin/member roles, add/remove/rename, leave (owner hand-off, last one out deletes), transfer, delete; invites pass blocks + `dmPolicy`; blocked members' messages collapsed and not unread; per-member receipts ("Seen by N"), typing, presence, signed images, soft delete, unread counts, live events, system lines; one-to-one conversations unchanged | ✅ | ✅ create-room dialog, member/settings panel, "Pack · N" badges, room header, sender names, system lines, collapsed blocked messages, "Seen by N", typing names | PackRoomsIT (7), DirectMessagesIT/RealtimeIT/DmMediaIT/PrivacyHardeningIT unchanged and passing, rooms.test.ts (8) |
| Reactions on posts, comments and messages: 10 curated kinds by key, one per kind per person, toggle, counts + "reacted by" (first 3, never people you blocked), visibility (private, held, removed, deleted, system, blocked sender), block in either direction refuses, grouped notifications, 60/min, live updates on `/topic/post.{id}` and the DM queue | ✅ | ✅ chips with full accessible names, keyboard picker (roving focus, Escape returns focus), batched loading, optimistic toggle | ReactionsIT (4), ReactionsLiveIT (1, real STOMP), logic.test.ts (11) |
| Automod: keyword (wildcards), RE2 regex, link limit, blocked domains, minimum account age, minimum karma; hold (spam hold flow) / remove (appealable, reason to author) / flag; strongest wins; mods and admins exempt; never bans; preview against recent content; hits list; full audit in `moderation_actions` | ✅ | ✅ Mod tools → Automod tab (editor with "Test against recent content", rule list, recent hits), "by automod" in the log | AutomodIT (10), AutomodMatcherTest (5, incl. `(a+)+$` and the time budget), helpers.test.ts (4) |
| Demo: one pack room ("Night shift": mira_k, dev_ostrander, nadia_sec, rustacean_ali) with messages and two message reactions, three reactions on c/programming's top post; through the real services, idempotent, `howlers.seed.packs` | ✅ | — | DemoSeedIT (new test) |
- Not verified in a browser (no browser run in this task): the new dialogs, panel, picker and Automod tab were checked by `npm run lint` (tsc + ESLint with jsx-a11y), Vitest and `npm run build` only.
- Gaps: automod doesn't re-check edits; reactions on removed content stay in the table but are never shown; presence in a room's inbox row is computed on the server (refetched on presence changes); the 50-member cap is enforced on add, not retroactively.
