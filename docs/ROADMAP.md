# Roadmap

Each phase ends with a working, tested application. ✅ done · ◐ partial · ☐ not started.

## Phase 0: Product & architecture ✅
Spec, architecture, database, API contract, design system, ranking, decisions.

## Phase 1: Foundation ✅
Spring Boot + Flyway + PostgreSQL, React/Vite/Tailwind design system with dark/light themes, auth (register,
login, refresh rotation and reuse detection, sessions, verification, reset), profiles, settings, onboarding,
responsive shell, Docker Compose, CI.

## Phase 2: Core community ✅
Communities (public/restricted/private), roles, rules, flairs, membership, posts (text/link/image/poll),
sanitised rich text, image upload, voting + karma, nested comments with lazy replies, feeds and ranking, search,
saved and hidden posts, notifications (grouped, polled).

## Phase 3: Moderation & quality ◐ (reports, queue, remove/approve/lock/pin, bans, appeals, audit log, blocking, admin dashboard, mute, spam heuristics ✅; topic reputation ☐)
`reports`, `moderation_actions` (auditable: moderator, action, target, reason, community, reversal), user
block/mute, mod queue, remove/approve/lock/pin, bans and timed suspensions, appeals, admin dashboard (users,
communities, flags, audit log), spam heuristics (link velocity, duplicate content), topic reputation v1
(vote events × topic, quality-weighted, anti-farming caps). Moderator UI in community settings.
**Topic reputation v1 ✅ (D-020):** recomputed from vote rows (incremental queue + nightly rebuild), quality-weighted,
self-vote, new-account and low-karma rules, per-pair and per-topic daily caps, removal reversal; profile badges,
per-topic leaderboards, expertise chips in threads.

## Phase 4: Knowledge ✅ (Collections, Context Mode, Digest, Community Pulse with live updates over WebSocket)
Smart Collections (collections, items for posts/comments, notes, tags, ordering, sharing), Context Mode
(stance labels on comments, argument map built from labels + votes, user-correctable AI suggestions),
Thread Digest (cached, versioned by thread change, every claim links to a source comment), Community Pulse
(polls/predictions with history; live results via WebSocket).

## Phase 5: Community AI ✅ (FTS retrieval; pgvector semantic layer deferred, see D-013)
`AiProvider` abstraction (chat/summarise/embed) configured by env; a local "extractive" fallback provider so it
runs with no paid API. Ingestion and chunking of posts/comments, FTS + pgvector hybrid retrieval, permission
filtering *before* retrieval (private communities, deleted content), answer + citations to real post/comment
ids, conversations, feedback.

## Phase 6: Media ✅ (upload-first podcasts, range-served media, transcripts, chapters, global player, AI script drafting; TTS/video generation and S3 driver deferred)
S3/MinIO storage driver and presigned uploads, podcast shows/episodes/chapters/transcripts/sources, audio and
video player (speed, chapters, resume, queue), discovery. Upload-first publishing; AI script/TTS generation only
when providers are configured, otherwise marked unavailable.

## Messages & real-time ✅ (cross-cutting)
One-to-one direct messages (text + images, read receipts, typing, presence, soft delete, search, `dmPolicy`, block
enforcement) and a STOMP-over-WebSocket channel that also pushes notification badges, Pulse updates, mod-queue changes, a
"N new posts" pill on feeds, and live comments and scores on post pages.
Single-node broker and presence; see D-017 for the multi-node path. Group chats are not planned yet.

## Phase 7: Files & privacy ✅ (private files, share links with access log, structural scanning hook, browser E2EE; ClamAV engine, S3 driver and private DM images deferred, see D-019)
Private file assets, share links (expiry, password, revocation, access log), validation and scanning hook.
Then E2EE as a separate capability: WebCrypto AES-GCM client-side, key in the URL fragment or wrapped with
PBKDF2/Argon2-derived keys, documented threat model and limits (no server preview or scanning).

## Phase 8: Moderator funding ✅ (demo mode only; no real provider, see D-022)
Funding pools, contributions (one-time and recurring), allocation policies, transparency report;
`PaymentProvider` abstraction with webhook verification. **Demo mode only** until a provider and compliance
review exist; demo transactions are labelled and never represent real money.
Update (D-034, D-035): Stripe hosted Checkout works in **test mode** (`FUNDING_PROVIDER=stripe`); live keys are refused without
`FUNDING_LIVE_MODE` + `FUNDING_COMPLIANCE_ACK`. Next: moderator payouts (Stripe Connect), automatic refunds for late payments.

## Phase 9: Moderator recognition ✅ (D-020)
Quality-weighted moderator score (consistency, response time, appeal outcomes, community health, peer
feedback, explicitly not raw removal counts), periods, badges, leaderboard, profiles.

## Phase 10: Polish ◐ (infrastructure ✅; accessibility audit and Playwright ☐)
Performance (Redis feed caching, image thumbnails), accessibility audit, Playwright end-to-end suite for the 23
flows in the brief, production deployment guide (VPS + TLS), observability dashboards.

**Infrastructure ✅ (D-023 to D-026):** S3/MinIO storage driver (private bucket, streamed multipart uploads, ranged
reads through the API); 320/960 px image variants with `srcset` and lazy loading; Redis first-page feed id cache with
per-viewer hydration and graceful fallback; private DM images through participant-signed URLs; Prometheus metrics on
an internal management port, JSON logs with trace ids and a Grafana dashboard; prod refuses dev secrets; HSTS and a
checked CSP; `docs/DEPLOYMENT.md` with `deploy/docker-compose.prod.yml` + Caddy (not executed: no Docker on the dev
machine); richer demo seed (moderation history, public collections, a demo funding pool) with an in-app demo notice.
Still open: accessibility audit, Playwright suite, presigned media URLs (only if API bandwidth becomes a problem),
migrating legacy public DM image URLs.
- ✅ Playwright end-to-end suite for the 23 flows, CI job, axe accessibility gate and keyboard checks (D-027).
- ☐ Manual screen-reader pass, triage of moderate/minor axe findings, load testing, and running the production compose file behind a real domain.

### Phase 6 follow-up: podcast audio generation ✅
Generate an episode's audio from its reviewed two-host script with ElevenLabs text-to-speech (D-036): stock voices only,
character estimate and per-community daily budget before starting, progress and cancel, timed transcript and chapters
built automatically, and a persistent "AI-generated voice" label (D-037). Video generation remains deferred.

## Privacy and safety hardening (D-028 to D-030) - done
- Image originals stripped of GPS/EXIF/XMP/IPTC losslessly (orientation kept); private files stay byte-identical.
- Optional ClamAV (clamd INSTREAM) after the structural checks, fail open or closed by config, honest UI labels; compose services.
- Legacy public DM images moved to private storage by an idempotent, recorded job; the compatibility send path can't create new ones.
- WebSockets closed on suspension, deactivation, deletion and role change; CONNECT checks the account status.
- Streaming E2EE decryption to disk (File System Access API) with an in-memory fallback and a large-file warning.
- Real ESLint (typescript-eslint, react-hooks, jsx-a11y) in `npm run lint`.
- Next: rescan stored files when signatures update; broadcast account revocations across nodes (Redis pub/sub or the broker relay).
