# Howlers frontend

React 18 + TypeScript (strict) + Vite 6 + Tailwind 3. Lives in `frontend/`.

## Run
```bash
cd frontend
npm install
npm run dev        # http://localhost:5173, proxies /api -> http://localhost:8080 (override with VITE_BACKEND_URL)
npm run build      # tsc -b && vite build
npm run lint       # type-check only (tsc -b --noEmit)
npm test           # vitest run
```

## Structure
```
src/
  app/            Providers (QueryClient, theme sync, session boot), router (lazy routes), route guards
  routes/         Page components, one folder per area (feed, posts, communities, profile, settings, auth, ...)
  layouts/        AppLayout (top bar, left nav, mobile tab bar, drawer), PublicLayout, RightRail, PageColumns
  features/<f>/   api.ts (endpoint calls) · hooks.ts (TanStack Query) · components/ · pure helpers (+ tests)
  components/ui/  Design-system primitives (Button, Field, Dialog, Menu, Tabs, Tooltip, Switch, Toast, States, ...)
  services/       apiClient (fetch, auth header, single-flight refresh), ApiError, query-key factory
  store/          Zustand auth store (access token in memory only)
  design-system/  brand.ts, tokens.css, BrandMark — the only place to rename/recolour the product
  hooks/ utils/ types/
```

## Auth model
- Access token kept **in memory** (Zustand). Refresh token is the backend's HttpOnly cookie.
- On boot `POST /auth/refresh` restores the session. Any 401 triggers one shared refresh, then the request is retried once.
- `RequireAuth` waits for boot, then redirects to `/login?next=…`; `RedirectIfAuthed` honours only same-origin `next` paths
  and routes users with `onboardingCompleted=false` to `/onboarding`.

## Pages → endpoints
| Page | Endpoints |
|---|---|
| Landing | `GET /communities?sort=popular` |
| Login / Register | `POST /auth/login`, `POST /auth/register`, `GET /auth/username-available` |
| Forgot / Reset / Verify | `POST /auth/forgot-password`, `/auth/reset-password`, `/auth/verify-email` |
| Onboarding | `GET /topics`, `GET /onboarding/suggestions`, `POST /onboarding/complete` |
| App shell | `GET /me/communities`, `GET /search/suggest`, `GET /notifications/unread-count` (30 s poll), `PATCH /users/me/settings` (theme) |
| Right rail | `GET /trending/posts`, `GET /communities/trending` |
| Home | `GET /feed?tab&t&cursor` |
| Post rows (everywhere) | `POST /posts/{id}/vote`, `PUT/DELETE /posts/{id}/save`, `PUT /posts/{id}/hide`, `DELETE /posts/{id}`, `POST /posts/{id}/poll/vote` |
| Post detail | `GET /posts/{id}`, `PATCH /posts/{id}`, `GET /communities/{slug}`, `GET /posts/{id}/comments`, `GET /comments/{id}/replies`, `POST /posts/{id}/comments`, `PATCH/DELETE /comments/{id}`, `POST /comments/{id}/vote` |
| Submit | `GET /me/communities`, `GET /communities/{slug}` (flairs, canPost), `POST /media`, `POST /posts` |
| Communities directory | `GET /topics`, `GET /communities?q&topic&sort&page` , `POST/DELETE /communities/{slug}/membership` |
| Create community | `GET /topics`, `POST /communities` |
| Community page | `GET /communities/{slug}`, `GET /communities/{slug}/posts`, `GET /communities/{slug}/members` |
| Community settings | `PATCH /communities/{slug}`, `PUT …/rules`, `PUT …/flairs`, `POST …/icon`, `POST …/banner`, `PUT …/members/{u}/role` |
| Explore | `GET /topics`, `GET /communities?sort=popular`, `GET /feed?tab=rising` |
| Search | `GET /search?q&type&community&sort&t&cursor` |
| Profile | `GET /users/{u}`, `GET /users/{u}/posts`, `GET /users/{u}/comments`, `POST/DELETE /users/{u}/follow` |
| Settings | `PATCH /users/me`, `POST /users/me/avatar|banner`, `POST /users/me/password`, `GET/DELETE /auth/sessions`, `POST /auth/resend-verification`, `POST /users/me/deactivate`, `DELETE /users/me`, `PATCH /users/me/settings` |
| Saved | `GET /me/saved` |
| Notifications | `GET /notifications`, `POST /notifications/{id}/read`, `POST /notifications/read-all` |

## Not built yet (shown honestly)
Nothing in the nav is a placeholder any more: every item routes to a real feature. The `/soon/:feature` page and the
`upcomingNav` list remain for future work and render nothing while the list is empty.

## Notes
- Post bodies are authored as HTML in TipTap and rendered from the server's sanitized `bodyHtml`. Comments are markdown rendered
  with `react-markdown` + `skipHtml` (no raw HTML); `@username` becomes an internal link.
- Optimistic updates: votes (posts + comments), saves, follows, joins, mark-read. All roll back on error.
- Comment threads: top level via cursor, replies merged lazily per parent; visual nesting caps at 4 (mobile) / 6 (desktop),
  deeper replies open in a focused “Continue this thread” view.

## Moderation & Pulse
| Area | Where | Endpoints |
|---|---|---|
| Report post/comment | PostMenu "Report", comment ⋯ "Report" → `ReportDialog` (rule titles + generic reasons) | `POST /reports` (409 → "already reported") |
| In-context mod tools | Post page PostMenu "Moderation" section (`canModerate`), comment ⋯ for mods via ThreadContext | `/mod/posts/{id}/{verb}`, `/mod/comments/{id}/{verb}`, ban dialog → `POST …/mod/bans` |
| Mod dashboard | `/c/:slug/mod` (Queue · Appeals · Bans · Log + stats tiles); "Mod tools" pill with open-report badge on community header | `…/mod/{queue,stats,log,bans,appeals}`, `/mod/appeals/{id}/resolve` |
| Affected user | `/mod-actions/:id` (decision + one-time appeal) | `GET/POST /me/mod-actions/{id}[/appeal]` |
| Ban banner | Community header when `viewer.banned`; Join hidden | — |
| Blocking | Profile Block/Unblock (confirm), Settings → Account → Blocked users; comments with `blocked` render a muted placeholder | `PUT/DELETE /users/{u}/block`, `GET /me/blocks` |
| Community Pulse | Community "Pulse" view (`?view=pulse`: active + history, mods "New Pulse"), right-rail card on community page, "Active pulses" on home rail | `/communities/{slug}/pulses`, `/pulses/{id}[/vote\|close\|resolve\|reasons]`, `/pulses/active` |

Open pulses refetch every 15 s. Pure helpers (`features/moderation/helpers.ts`, `features/pulse/helpers.ts`) are unit-tested.

## Podcasts

| Route | What it does | Endpoints |
|---|---|---|
| `/podcasts` | Discovery: featured, continue listening, saved, recent shelves, plus a cursor list filtered by category chips, community and sort; History tab for signed-in users | `/podcasts/home`, `/podcasts`, `/me/podcasts/history` |
| `/podcasts/:id` | Episode: header actions (play/resume, queue, save, share, download, edit in studio), inline video with subtitles, chapters, searchable synced transcript, sources | `/podcasts/episodes/{id}`, `…/transcript?q=`, `…/transcript.vtt`, `…/save`, `…/progress` |
| `/c/:slug?view=podcasts` | The community's published episodes; Studio link for moderators | `/podcasts?community=` |
| `/c/:slug/podcasts/studio` | Studio list by status, "New episode" | `/communities/{slug}/podcasts/studio`, `POST …/podcasts/episodes` |
| `/c/:slug/podcasts/studio/:id` | Editor: details, cover, media upload (XHR progress, client-measured duration), transcript import, chapters, sources, script with AI drafting (polls while `GENERATING`), and the Draft → In review → Published workflow with a checklist | `PATCH /podcasts/episodes/{id}`, `…/media`, `…/thumbnail`, `PUT …/transcript`, `…/submit`, `…/publish`, `…/unpublish`, `…/draft-script`, `/podcasts/capabilities` |

**Global player.** `store/player.ts` (zustand, persisted: current track, queue, per-episode positions, rate) drives the one
`<audio>` element in `features/podcasts/components/MiniPlayer.tsx`. That element is mounted in `AppLayout`, so audio keeps
playing across navigation. Video plays inline on the episode page only.
- **Progress:** saved through `createProgressSaver` (throttled to 15 s, forced on pause and seek, `fetch` keepalive on
  `pagehide`); an episode is marked complete near the end.
- **Media Session:** metadata and handlers provide lock-screen and headset controls.
- **Resume:** playback starts from the server position, falling back to the position saved on this device; finished
  episodes restart from the beginning.

## Messages & real-time
- **Connection** (`services/realtime.ts`, `features/realtime/RealtimeProvider.tsx`): `@stomp/stompjs` 7.5.0 over a plain
  WebSocket at `ws(s)://<host>/ws` (Vite proxies `/ws` with `ws: true`; nginx upgrades it with a 1 h read timeout). It connects
  only while signed in, sends `Authorization: Bearer` in CONNECT (read fresh in `beforeConnect`), refreshes the token after a
  rejected CONNECT, and backs off exponentially from 1 s to 30 s. It reconnects when the signed-in user changes and disconnects on
  logout. A same-user token refresh keeps the open socket, because the server authenticates at CONNECT.
- **Events** on `/user/queue/events` update React Query caches: MESSAGE / MESSAGE_DELETED patch the thread pages; READ moves
  the "Seen" marker (or clears my unread count from another tab); TYPING feeds a zustand store with a 5 s expiry; PRESENCE flips
  online dots; NOTIFICATION / UNREAD_MESSAGES set the badge counts. Polling (notifications 60 s, DM unread 60 s, threads 15 s,
  pulses 15 s) runs **only while the socket is down**.
- **Hooks**: `useRealtimeStatus()` and `useTopic(destination | null, cb)` (subscriptions survive reconnects). PulseCard subscribes
  to `/topic/pulse.{id}` while the pulse is open; the mod dashboard subscribes to `/topic/modqueue.{communityId}` only once the
  community confirms that you're a moderator, because an unauthorized SUBSCRIBE is rejected.
- **UI**: `/messages` and `/messages/:id` show two panes on desktop and one at a time on mobile. The list has online dots, an
  excerpt, time, an unread badge, debounced `?q=` search and a New message dialog with username autocomplete. The thread groups
  messages by day and by sender in 5-minute runs, puts my messages on the right in the brand colour, shows "Seen" under the newest
  message they've read, a typing indicator, and "Message deleted". Older messages load when you scroll to the top, and the scroll
  position is kept. Sends are optimistic, with Retry/Discard on failure; images upload through `POST /media` (≤4). The composer
  grows as you type, Enter sends and Shift+Enter adds a line. TYPING goes out at most once per 2 s. When `canMessage` is false,
  the composer is replaced by `cannotMessageReason`. The thread is marked read while it's visible.
- **Entry points**: a top-bar icon with the unread count; Messages in the left nav (`/soon/messages` redirects); a Message
  button on profiles when `canMessage`; Settings → Preferences → "Who can message you" (`dmPolicy`).
- Pure logic lives in `features/messages/helpers.ts` (grouping, appendMessage/applyDelete/applyRead, page helpers,
  `seenMessageId`, throttle) and `features/realtime/typingStore.ts`, all covered by `helpers.test.ts`.

## Live feeds and threads
Signed-in only (anonymous users have no socket). Code in `features/live/`; event types in `types/live.ts`.
- **"N new posts" pill** (`useNewPosts` + `NewPostsPill`): the home feed listens on `/topic/posts`, a community feed on
  `/topic/community.{id}` (subscribed only when the community detail says `viewer.canView`, since a rejected SUBSCRIBE closes
  the socket). Frames are counted, never inserted: own posts are ignored and ids are deduped. New / Hot / Rising / Top count
  everything; Best (for you) counts posts in joined communities (`useMyCommunities`; everything until that list loads);
  Following is people-based, so it shows no pill. The pill is a zero-height sticky row under the top bar, so the list never
  shifts; clicking it scrolls to the top, trims the infinite query to its first page and refetches it
  (`refetchFromFirstPage` in `posts/cache.ts`), then clears the count. The count resets when the tab, sort or range changes. A
  polite `role="status"` region announces the count.
- **Live post page** (`usePostLive`, subscribed to `/topic/post.{id}` once the post detail loaded):
  - `POST_STATS` patches `score` and `commentCount` everywhere the post is cached (detail, feeds, profile, search) and never
    touches `myVote`; it is skipped while my own vote on the post is in flight (mutation key `mk.postVote`).
  - `COMMENT_SCORES` patches scores in every cached sort order of the thread, skipping comments I'm voting on (`mk.commentVote`).
  - `COMMENT_CREATED` from someone else (mine are inserted by the create mutation) fetches `GET /comments/{id}` unless the
    comment is already cached. Top-level comments go first on page one; replies are appended to a cached parent (its
    `replyCount` grows, so "N more replies" for unloaded children stays right). Replies to a parent that isn't loaded are
    skipped (POST_STATS still updates the total); a 404 is ignored. Arrivals are highlighted for 4 s from a small zustand
    store (`newComments.ts`) with a brand tint and left rule that fades out.
- `patchPostEverywhere` and `patchForests` now keep untouched lists and pages by reference, so a 2 s stats batch only
  re-renders what changed. Pure logic is tested in `features/live/logic.test.ts` and `features/comments/tree.test.ts`.

## Moderator funding (demo mode)
Code in `features/funding/`; types in `types/funding.ts`. Every funding view starts with `DemoBanner` ("DEMO, no real money", warning
colours, `role="note"`), and every amount is rendered through `MoneyAmount`, which puts a DEMO badge next to it (screen readers hear
"Demo, no real money").

| Route | What it does | Endpoints |
|---|---|---|
| `/c/:slug?view=funding` | "Funding" tab: current pools with progress bars, Contribute (members), New pool (moderators), Close now / Allocate (owner), allocation policy panel (owner edits, others read), past periods | `GET /communities/{slug}/funding`, `POST …/funding/pools`, `PUT …/funding/policy`, `POST /funding/pools/{id}/contributions`, `GET …/allocation-preview`, `POST …/close` |
| `/funding` (nav: Moderator funding) | Open pools in my communities, my monthly contributions with Cancel (confirm dialog), contribution history | `GET /me/funding`, `POST /me/funding/subscriptions/{id}/cancel` |
| `/funding/pools/:id` | Public transparency report: totals, reconciliation check, anonymous/named contributors, allocations with policy and activity, append-only ledger | `GET /funding/pools/{id}/report`, `GET /funding/status` |

- **Contribute dialog**: one time or monthly, preset amounts or a typed amount, "show my name" off by default. The submit button
  repeats the amount and "(demo)".
- **Close dialog**: for EQUAL and ACTIVITY it shows the server's preview of each moderator's share. For MANUAL it asks for an
  amount per moderator and shows a live "left to allocate" figure. It also requires a public note, and stays disabled until the
  amounts add up exactly.
- `/soon/funding` redirects to `/funding`, and the "Coming next" nav entry has been replaced by the real route.
- Pure logic lives in `features/funding/money.ts` (format/parse minor units with integer maths only, bounds, presets, progress) and is
  tested in `money.test.ts`.

### Funding with Stripe (D-034)
- `components/Demo.tsx` became `components/Mode.tsx`: `ModeBanner`, `ModeBadge` and `MoneyAmount` take their text from the API
  (`FundingStatus.label`/`notice`/`payouts`, `Money.label` per row): "DEMO, no real money", "TEST MODE: no real money", or no
  badge for live money. Buttons say "(demo)" or "(test mode)" (`contributeSuffix`).
- **Contribute dialog**: when the response has `checkout`, the browser goes to `checkout.url` (Stripe Checkout), and the button
  shows "Opening Stripe Checkout…". Test mode says to use a Stripe test card. The site never sees card data or any Stripe key.
- **Return**: `/funding/pools/:id?checkout=success&session_id=…` shows `CheckoutReturn`, which polls
  `POST /funding/checkouts/confirm` (about a minute, slowing down) until CONFIRMED, FAILED or EXPIRED, then refreshes funding
  queries. If Stripe hasn't confirmed by then it shows "Payment pending" (it counts once confirmed).
  `?checkout=cancelled&pending=…` calls the cancel endpoint and says nothing was charged. URL parsing and the poll schedule
  are tested in `checkout.test.ts`.
- My contributions shows "billed monthly by Stripe", a failed-renewal warning, and refunded amounts. The transparency report
  labels allocations "Allocated, not paid out" and marks refund ledger rows.
## Files & share links
| Route | What it does | Endpoints |
|---|---|---|
| `/files` (nav "Files", signed in) | Usage meter against the quota. A drag-and-drop or picker upload zone with per-file XHR progress and cancel; uploads run one after another. An "Encrypt in browser" switch with an explanation, an optional label visible to the server, and "Remember the key in this browser". The library has name search, infinite scroll, a type, size and date per row, a "Checked" badge (tooltip says it isn't antivirus) or "E2EE · not scanned", active link count, Download, Share, Links and access log, Rename and Delete (confirmed) | `/me/files`, `/me/files/usage`, `POST /me/files` (XHR), `PATCH/DELETE /me/files/{id}`, `POST …/download-url`, `GET …/content` |
| Share dialog | Expiry presets (1 h to 30 d, or never), access password, download limit. For E2EE files it also asks where the key goes: in the link (`#k=`) or a passphrase (with confirmation, at least 10 characters). It first checks the key against the file's encrypted metadata, fetched with a Range request. The full link appears once with Copy. After an encrypted upload it also shows the recovery key | `POST /me/files/{id}/links`, `GET …/content` (Range) |
| Links dialog | Each link with status (active, expired, turned off, limit reached), password and key-mode badges, downloads, last activity and "Turn off". Each link's access log is expandable and paged: signed-in viewers by name, anonymous visitors as a short daily hash | `GET /me/files/{id}/links`, `DELETE …/links/{linkId}`, `GET …/links/{linkId}/access` |
| `/s/:token` (public layout, works signed out) | Name, size, type, expiry, downloads left, and an honest scan note. Asks for the password and, for E2EE, the passphrase, or reads `#k=` from the fragment; a link without its key gets a clear message. Plain files download through the signed URL. E2EE files are fetched as ciphertext with progress, decrypted in the browser and saved under their original name. A wrong passphrase is caught before any counted download. Expired, revoked or exhausted links show the server's reason | `GET /share/{token}`, `POST /share/{token}/unlock`, `GET /downloads/{grant}` |
| `/security/e2ee` | The threat model in plain language (mirrors `docs/SECURITY_E2EE.md`), linked from every E2EE explanation | — |

- **Crypto** (`features/files/crypto.ts`): WebCrypto AES-256-GCM, a chunked container (`STRATAE2` v1, 1 MiB chunks,
  per-chunk IV and tag, header, index and last flag in the AAD, encrypted name and type), raw key export as base64url for
  the fragment, PBKDF2-SHA256 at 600k iterations to wrap keys. Tested in `crypto.test.ts` under Vitest's Node environment
  (round trip, wrong key, tampering, truncation, reordering, wrong passphrase).
- **Key memory** (`features/files/keyStore.ts`): optional per-device localStorage map from file id to key. Every access
  is wrapped in try/catch and never uploaded.
- Pure helpers (`features/files/helpers.ts`: sizes, expiry presets, share URLs, labels, passphrase rules) are covered by `helpers.test.ts`.
## Reputation and moderator recognition
Code in `features/reputation/` (`api.ts`, pure `logic.ts` + `logic.test.ts`, components); types in `types/recognition.ts`.
- **Routes.** `/leaderboard` (tabs: Moderators with month and community selectors, Topics with each topic's top 3) and
  `/topics/:slug/leaders`. "Leaderboard" moved from "Coming next" into the main nav; `/soon/leaderboard` redirects.
- **Moderator rows** expand (a disclosure button with `aria-expanded`; the name links stay outside it) into `ScoreBreakdown`: each component's value, its renormalised weight, the
  points it adds and the raw rows behind it in words. `explainScore` re-derives the server's score from the components
  (tested), and the rail shows `FormulaPanel` built from the server's formula constants, so the page can't drift.
- **Thread chips.** `ExpertiseChip` sits next to the author name in `CommentItem`; all chips on a thread read one cached
  `GET /posts/{id}/expertise` query.
- **Profile.** `TopicStandings` (topic badges with rank) and `ModeratorRecord` (monthly scores and badges) in the About
  rail; both hide when the server says `hidden`.
- **Mod tools → Team** tab: `PeerEndorsements` ("Helpful +1" with an optional note, one per co-moderator per month).
- **Settings → Preferences → Moderator recognition**: "Show me on moderator leaderboards" (`/me/moderator-settings`).
- Empty states say why a board is empty (no qualifying activity yet) instead of showing placeholder rankings.
## Admin, mutes and spam holds (D-021)
| Route | What it shows | API |
|---|---|---|
| `/admin` | Admin dashboard (ADMIN only; others see an "Admins only" page and the server refuses every call). Tabs: **Overview** (real counts, 14-day sign-up bar chart in Recharts with an accessible text label), **Users** (debounced prefix search, status filter, detail dialog with counts, moderated communities, admin history and suspend/unsuspend/promote/demote with a reason), **Communities** (search, filter, quarantine/ban/restore with a required reason), **Flags** (spam holds and rejections across communities; open reports grouped per target with remove/dismiss), **Audit log** | `/admin/**` |
| `/c/:slug/mod?tab=held` | "Held" tab in mod tools: content held by the spam checks, with Approve and publish / Remove | `/communities/{slug}/mod/held`, `/mod/flags/{id}/…` |
| `/settings?tab=muted` | Muted people and communities with Unmute | `/me/mutes`, `/users/{u}/mute`, `/communities/{slug}/mute` |
- **Entry points:** "Admin dashboard" in the account menu for admins. Mute button on profiles (next to Block) and an icon
  button in the community header. Mute state comes only from `/me/mutes` (`features/mutes/api.ts`), never from public payloads.
- **Held content:** feed cards and the post page show "Awaiting review"; the post page explains who can see it. A held
  comment shows an "Awaiting review" tag, and the author gets a toast instead of a comment-count bump. Moderators see
  "Approve and publish" in the post and comment menus.
- **Muted comments** start collapsed with a "muted" hint and expand on click.
- **Quarantined/banned communities** show a banner with the admins' reason; a banned community shows a closed state.
- **Feed mod tools:** `PostCard`/`PostRow` pass `post.viewer?.canModerate` to `PostMenu`, so moderators get remove,
  approve, lock, pin and ban in feeds. `patchPostEverywhere` keeps the detail's wider `viewer` when patching from a feed row.
- **Onboarding:** "Skip for now" calls `POST /onboarding/skip` and stores the returned `Me`.
- Pure logic: `features/admin/helpers.ts` (chart series, offered account actions, audit sentences, rule labels) and
  `features/mutes/api.ts` lookups, tested in `helpers.test.ts` and `mutes.test.ts`.

## Infrastructure polish (Phase 10)
- **Responsive images** (`utils/images.ts`, tested in `images.test.ts`): `responsiveImage(media)` builds `src` + `srcSet` with
  width descriptors from `thumbnails` (320/960 variants plus the original's real width); `FEED_IMAGE_SIZES` is the `sizes`
  for feed and post images. `sizedUrl(url, 320|960)` appends `?w=` to our own media URLs only (external, `blob:` and
  `data:` URLs are left alone). Used by `PostCard` (card media), `PostRow` (compact thumbnail), the post page gallery,
  `Avatar` (all avatars and community icons ask for 320 px), the right-rail and collection thumbnails. All of them load
  lazily with `decoding="async"`. Banners keep the original (960 px would look soft on wide screens).
- **Private DM images** (`features/messages`): the composer uploads through `dmApi.uploadMedia` (`POST /me/dm-media`).
  `DmImage` renders a signed image with `srcset`; signed URLs expire, so a failed load invalidates the thread query once
  to fetch fresh URLs. `DmMessage.media` is `DmMedia[]` (`types/messages.ts`).
- **Demo data notice** (`features/demo`): `DemoNotice` sits under the top bar when `GET /demo/status` says the database
  holds the fictional demo data. It is a `role="note"` strip with a labelled dismiss button; dismissal is remembered in
  localStorage (wrapped in try/catch, tested in `api.test.ts`), so it shows again in a private window.
## End-to-end tests (Playwright) and accessibility audit (D-027)
`frontend/e2e/`, Chromium only, run with `npm run e2e` (`playwright.config.ts`). The suite needs a running backend with
the demo seed and a frontend server that proxies `/api` and `/ws`. Playwright does **not** start anything by default.

| Spec | Flows |
|---|---|
| `01-account` | 1 register (a fresh `e2e_*` user, one per run), 3 onboarding (topic, community, theme), 2 log out and log in, 4 create a community |
| `02-posts` | 5 join (leaves first if already a member), 6 create a text post, 7 vote, 8 comment, 9 reply, 10 save, 11 new collection from the post menu, 12 search |
| `03-knowledge` | 13 Context Mode, 14 Digest (+ refresh), 15 Community AI with one question: passes with a cited answer (the live AI model or the offline engine) or the honest notice |
| `04-pulse` | 16 create a Pulse (UI, 1 hour), 17 vote (results hidden until you vote) |
| `05-files` | 18 upload, share link, download signed out (content compared); 18b E2EE: encrypt in the browser, check the upload never contained the name or plaintext, open the `#k=` link in a fresh context, decrypt, read the decrypted Blob inside the page |
| `06-podcast-funding-leaderboard` | 19 play an episode (the player starts and the position advances; on a fresh database a clearly labelled 20 s WAV test tone is published through the studio API and deleted afterwards), 20 demo contribution (DEMO label asserted), 21 leaderboard (rows and breakdown, or the honest empty state) |
| `07-moderation-messages` | 22 report → remove from the queue → the author's notification, appeal page and "removed" notice (the moderator isn't named); 23 DM between two contexts, arriving live with no reload |
| `08-accessibility` | axe-core on 10 pages × dark/light × 1280 px/375 px; serious and critical violations fail (see `docs/ACCESSIBILITY.md`) |
| `09-keyboard` | skip link, visible focus ring, dialog focus trap, Escape and focus return |

**Repeatable on one database.** Every test creates uniquely named data (`uid()`), arranges what isn't the flow under
test through the API (`sessions.api(user)`), and uses accessible locators only (`getByRole`, `getByLabel`). Tests run
serially in one worker. Each demo user gets one long-lived browser context, because the refresh cookie rotates on every
page boot and reusing an old one revokes the session; `gotoAuthed` waits for the boot refresh. Locally the latest cookie
per user is kept in `e2e/.auth/` (git-ignored) to stay under the login rate limit (10 per account per 10 min); a stale
cookie falls back to a fresh login. Registration is limited to 5 per IP per hour, so the suite registers once per run.
Nothing reads email: no flow requires a verified address.

**Running it locally.**
```bash
cd frontend
npx playwright install chromium            # once
# Backend on :8080 with the seed. If its CORS_ALLOWED_ORIGINS doesn't list your test server's origin, let the dev proxy
# present an allowed one (it only rewrites requests whose Origin is the dev server's own localhost origin):
HOWLERS_DEV_PROXY_ORIGIN=http://localhost:5173 npx vite --port 5174 --strictPort
npm run e2e                                 # E2E_BASE_URL defaults to http://localhost:5174
E2E_START_SERVER=1 npm run e2e              # or let Playwright start `vite --port 5174` itself
AXE_ONLY="post page,files" npx playwright test 08-accessibility   # audit a subset
npm run e2e:report                          # HTML report (CI); traces are kept for failed tests
```
Vitest ignores `e2e/**`; `tsc -b` type-checks the specs through `tsconfig.e2e.json`.

**CI.** The `e2e` job in `.github/workflows/ci.yml` starts PostgreSQL and Redis service containers, runs the backend
jar with the seed on, `AI_PROVIDER=offline` and an empty `ANTHROPIC_API_KEY`, serves the built frontend with
`vite preview --port 5174` (same proxy) with `CORS_ALLOWED_ORIGINS=http://localhost:5174`, waits for readiness and runs
the suite. It references no repository secrets (every value is a throwaway CI-only string), checks out without persisted
credentials, and uploads the report and traces only when it fails.

## Email verification states (D-038)
`/verify-email?token=` (`routes/auth/VerifyEmailPage.tsx`) calls `POST /auth/verify-email` once and maps the answer
with `features/auth/verifyOutcome.ts` (tested): **verified**, **already verified**, **expired or replaced**
(`LINK_EXPIRED`), **not recognised** (`LINK_INVALID`), **too many attempts** (429), and a missing token. For expired,
invalid or missing links a signed-in user gets "Send a new verification email" (`POST /auth/resend-verification`,
3/hour; the 429 message is shown inline); a signed-out visitor is pointed to log in and use Settings › Account, where
the existing resend button lives. The reset page links back to `/forgot-password` for expired links.
## Podcast audio generation
`features/podcasts/components/GenerateAudioPanel.tsx` appears in the studio editor only when `capabilities.audioGeneration`
is true (otherwise the media panel keeps saying generation isn't configured). It shows the two voices, the estimated
characters, requests and minutes from the saved script, the community's remaining daily budget and any blockers, then
progress (polling `GET …/audio-generation` every 3 s while a job is active) with Cancel. When a job turns READY it refetches
the episode, transcript and studio list. `AiVoiceBadge` is the persistent "AI-generated voice" label used on the episode
page, the mini player (`PlayerTrack.aiVoice`), episode cards, the studio media panel and the review step; its full
disclosure text (`aiVoiceDescription` in `features/podcasts/audioGeneration.ts`) is in the title and in screen-reader text.
## LLM digest and AI stance suggestions (D-031, D-032)
- `features/insight/engine.ts` (pure, tested in `engine.test.ts`): `digestEngineLabel` ("Summarised by AI" for `llm-*`
  engines, "Extractive summary" otherwise; unknown engines are never labelled AI), `lineSources`, `coverageNote`,
  `stanceOrigin` and `contextPollInterval`.
- `DigestPanel` shows an engine badge, the coverage note, and an info banner with the server's `notice` when the
  extractive engine stood in. Paraphrased points have no quotation marks; verbatim quotes keep them ("In their own words"
  for AI digests). Every cited comment gets its own clickable source link. Open questions from the model sit above the
  unanswered question comments.
- `ContextModeView` marks AI-suggested stances with a small "AI-suggested" chip (tooltip: use Label to correct it), counts
  AI vs rule suggestions in the stats line, and shows the `aiStances` note. `useContextView` polls every 3 s while
  `aiStances.state` is `PENDING`.
## Landing page (Howlers)
`/` is `routes/landing/LandingPage.tsx`: hero (`HeroArt`, `NightSky`, `design-system/Pups`), a marquee of real communities
(`GET /communities?sort=popular&size=12`, falls back to feature names), six feature rows (`FeatureSections`), safety
cards, a searchable FAQ (`faq.ts`, `filterFaq`) and a closing CTA (`SafetyFaq.tsx`). Signed-in visitors see "Open your
feed" instead of "Join the pack"; there's no redirect. Motion helpers live in `motion.ts` (intro sequencer, reveal
observer, play gate, parallax/tilt maths) with Vitest coverage in `motion.test.ts`; styles and keyframes in
`landing.css`. The page is always dark (see DESIGN_SYSTEM.md, "Landing page"). E2E: `e2e/10-landing.spec.ts`, and the
landing stays in the axe audit (`08-accessibility`).
## Privacy and safety hardening (D-028 to D-030)
- **Scan labels** (`features/files/helpers.ts → scanSummary`). Files and share pages use `scanEngine`. They say
  "Scanned by ClamAV: no known malware found" only for `CLAMAV`, and "Checked: passed type and structure checks. Not an
  antivirus scan" otherwise. The badge reads "Scanned" or "Checked". `scanDetail` (tooltip) explains a fail-open upload.
- **Streaming E2EE decryption** (`features/files/streamDecrypt.ts`, `components/StreamingSave.tsx`). For E2EE files of
  64 MB or more in browsers with `showSaveFilePicker`, the share page and the Files page open the download as a stream
  (`openDownloadResponse` / `openOwnContentResponse`). `openContainerStream` verifies the header and metadata, and
  `StreamingSavePrompt` then asks for a click ("Choose where to save"), because the save dialog needs a user gesture.
  Each chunk is decrypted and written as it arrives, with a progress percentage. Dismissing the dialog lets you try
  again. A failure aborts the file and says so. Elsewhere the in-memory path is unchanged, and `MemoryWarning` explains
  the risk above 500 MB. `ByteReader` and `sealedFrames` (look-ahead for the last chunk) are pure and tested in
  `streamDecrypt.test.ts` (6 tests), including truncation at a chunk boundary, tampering, a wrong key and empty files.
- **ESLint** (`eslint.config.js`, flat config): `@eslint/js` recommended, `typescript-eslint` recommended,
  `react-hooks` recommended and `jsx-a11y` recommended. `npm run lint` = `tsc -b --noEmit && eslint .` (the CI step is
  unchanged). One rule is configured rather than disabled: `label-has-associated-control` knows that `Input`,
  `Textarea` and `Select` render native controls. Fixes the linter found:
  - `useMemo` for transcript items (exhaustive-deps).
  - Mod badges' tooltip triggers are buttons, not `tabIndex` spans.
  - DM image alt text no longer says "image".
  - `autoFocus` removed from the full-page sign-in, sign-up, forgot-password and reset-password forms.

  The remaining inline disables each state their reason:
  - autofocus in dialogs and reply/edit boxes the user just opened;
  - whole-card click shortcuts whose title is the real link;
  - the poll's click containment;
  - the search combobox, whose keyboard handling lives on the input;
  - podcast media whose captions or transcript are provided elsewhere or conditionally;
  - the control-character regex in `safeFileName`.
- **Live sockets.** A suspended, deactivated or deleted account's socket is closed by the server (1008) and reconnects
  are refused. The existing client keeps retrying with backoff, and its next REST call gets 401/403, which signs it out.

## Installable app, link previews and push (D-039, D-040)
- **Manifest and icons** in `public/` (`manifest.webmanifest`, `icons/*`, `og/howlers.png`). The PNGs come from the wolf
  in `public/favicon.svg`: change the mark, then `npm run brand:images` (headless Chromium via Playwright) and commit.
- **Service worker**: source `pwa/sw.js`, emitted as `/sw.js` by `pwa/serviceWorkerPlugin.ts` with the build version and
  precache list. `features/pwa/serviceWorker.ts` registers it in production builds only (`VITE_ENABLE_SW_IN_DEV=true`
  registers a no-cache dev variant), checks for updates hourly, and `UpdatePrompt` (mounted by `PwaRoot` in
  `Providers`) offers "Reload" when a new version waits. Tests: `features/pwa/sw.test.ts` runs the worker against a
  fake worker scope.
- **SEO**: `features/seo/canonical.ts` keeps `<link rel="canonical">` in step with the router (from `main.tsx`).
  Crawlers that don't run JS get the backend's preview document through nginx; `index.html` carries default OG tags.
- **Push**: `features/push/` (`api.ts`, `pushClient.ts` for subscribe/unsubscribe/resync, `pushLogic.ts` for pure
  helpers with tests, `components/PushSettings.tsx`). The card sits under Settings › Preferences and says honestly why
  push is unavailable: server without VAPID keys, unsupported or insecure browser, no service worker (dev), or iOS
  outside an installed Home Screen app. The device id is remembered per user in `localStorage`; signing out
  unsubscribes this browser first (bounded to 2.5 s).
## Passkeys, two-factor authentication and sign-in alerts (D-041, D-042)
- `features/accountSecurity/`: `api.ts` (all `/auth/security`, `/auth/2fa/**`, `/auth/passkeys/**`, passkey sign-in and
  the login second step), `hooks.ts` (queries under `['account-security']`, `createPasskey`, `signInWithPasskey`),
  `webauthn.ts` (base64url, options/credential JSON conversion, `passkeySupport`, error messages) and `logic.ts`
  (code classification, method labels, recovery-code file text). Types in `types/accountSecurity.ts`.
- The WebAuthn API is called directly; no library. `passkeySupport()` needs `isSecureContext` (HTTPS or
  `http://localhost`) and `PublicKeyCredential`; otherwise the buttons are disabled with the reason shown.
- Login: `POST /auth/login` may return `MfaChallenge` instead of a session (`isMfaChallenge`); `LoginForm` then
  renders `SecondStepForm` (authenticator code, or a recovery code via "Lost your device?"). An expired token sends
  the user back to the password step with the server's message. "Sign in with a passkey" sits under the form on
  `/login` and needs no username; a passkey sign-in has no code step.
- Settings > Account & security: Passkeys card (add with optional name, rename, remove with confirmation, "Synced" and
  "Blocked" badges), Two-factor card (enable dialog: password, QR from the server's SVG shown as an `<img>` data URL,
  manual key, code, then recovery codes with copy/download; the codes step only closes with its button), Sign-in
  alerts switch, and the sessions list with method badges and "Sign out everywhere else". The sessions card is
  wrapped in `#sessions`, the target of the link in new-sign-in emails.
- The code field in the second step autofocuses: it appears in response to the user's submit and is the only control
  that matters there.
## Moderator payouts (Stripe Connect, D-046, D-047)
- **`/funding/payouts`** (`routes/funding/PayoutsPage.tsx`, linked from the funding hub): `ConnectAccountCard` shows the
  account status from the API with one next step ("Connect with Stripe", "Resume onboarding", "Check status"). Starting
  onboarding fetches a single-use URL and only follows it if it is an `https://…stripe.com` address (`isStripeOnboardingUrl`).
  Stripe sends the browser back with `?connect=return` (the page re-reads the account through the server) or
  `?connect=refresh` (the link expired; the page says so and offers a new one). When Connect is off on the server the card
  says so in the server's words, with no button. `PayoutList` lists each allocation with its label ("Paid out (test mode)",
  "Held: connect your account", …) and amount badge; totals are per currency, from the server.
- **Transparency report**: closed Stripe pools show each allocation's payout badge, the payout reconciliation line
  (allocated = paid out + held), and PAYOUT / reversed-transfer ledger rows. Demo pools keep "Allocated, not paid out".
- **Admin → Payments** (`features/admin/components/PaymentsTab.tsx`): `StripeStatusPanel` (mode, which secrets are set,
  last webhook, Connect on/off, counts, endpoint URL with copy button and the two event lists, "Run payouts now") and
  `PayoutReviews` (refunds after payout, reversed or failing transfers; resolve with a note).
- Pure helpers and tests: `features/funding/payouts.ts` / `payouts.test.ts`; API hooks in `features/funding/payoutsApi.ts`;
  types in `types/payouts.ts`.
## Pack rooms, reactions and automod (D-043 to D-045)
- **Pack rooms** live in `features/messages` next to one-to-one messages. `rooms.ts` holds the pure helpers (receipt
  aggregation "Seen by N", permission mirrors, username parsing, draft validation, typing labels; `rooms.test.ts`).
  `CreateRoomDialog` (name, `IconChoice` radio group, `PeoplePicker` with search suggestions, paste of several names,
  removable chips) creates a room and opens it. `RoomPanel` (a dialog from the thread header's "Members" button) lists
  members with role badges and online dots, and shows add/remove/role/ownership actions, rename and icon, leave and
  delete only to people allowed to use them (the server decides). The conversation list shows a room tile with its emoji
  and a "Pack · N" badge. In the thread, system lines are centred text in their own group (`groupMessages` never merges
  them into a run), other people's runs carry the sender's name, messages from someone you blocked render as a collapsed
  "Message from someone you blocked", and the aggregated receipt sits under your newest message. Realtime handles
  `ROOM_UPDATED` (refetch), `ROOM_REMOVED` (drop the room's caches) and member READ receipts (patch the room detail).
- **Reactions** live in `features/reactions`. `logic.ts` mirrors the server catalog and holds the optimistic toggle,
  "reacted by" labels, chip accessible names and the picker's roving-focus key map; `batcher.ts` coalesces per-item
  loads made in the same tick into one `GET /reactions?ids=` per target type (`logic.test.ts` covers both).
  `ReactionBar` renders chips (buttons with `aria-pressed` and a full label such as "Fire: 3 reactions, including yours.
  You, ana and 1 other"; read-only for signed-out readers) and, unless `withPicker={false}`, an `AddReactionButton`:
  a toolbar of ten labelled buttons ("React with Fire") with arrow/Home/End navigation, Escape to close and focus back on
  the trigger. Posts show the bar under the body, comments in their action row, messages under the bubble with the add
  button revealed on hover or focus. `usePostLive` and the realtime provider refetch a target on `REACTIONS`.
- **Automod** is a Mod tools tab (`features/automod`). `helpers.ts` (labels, draft validation, one-line rule summaries,
  request shaping; `helpers.test.ts`) feeds `AutomodTab`: a rule editor with type-specific fields, action and scope
  selects, the author-facing reason, an on/off switch and "Test against recent content" (shows matches, marks exempt
  authors, changes nothing), the rule list with turn on/off, edit and delete, and recent hits. The tab states that
  moderators are exempt, automod never bans, and edits aren't re-checked yet. The mod log labels automod rows "by
  automod", and the Held tab and held banners mention automod holds.
## Mascots in app states (D-048)
`design-system/Mascot.tsx` renders a wolf pup (`pose`: `sit`, `wave`, `howl`, `headphones`, `sleepy`) with theme-aware
colours from `mascot.css`; it is `aria-hidden` and idles only when motion is allowed. Use it through
`EmptyState`'s `mascot` prop (and `headingLevel={1}` when the empty state is the whole page, as on the 404).
`ErrorState` picks the sleepy pup by itself for offline (status 0) and maintenance (502/503/504) errors via
`connectionTrouble()` in `services/apiError.ts`. Used on: home feed tabs, search, messages, files, share links,
notifications, podcasts (list, community tab, studio), mod Queue and Held, admin reports, leaderboards, the 404,
`RouteError` and `PageSpinner`. Keep the copy short and warm, keep every fact the old copy had, and don't change the
action button.

## Keyboard walkthrough checks
`e2e/09-keyboard.spec.ts` also covers menus (Enter opens, arrows move, Escape closes and refocuses the button), the
search suggestions (arrow keys + `aria-activedescendant`, Escape), the mobile navigation drawer (focus inside, Escape
returns focus to "Open navigation") and the skip link on newer pages. See docs/ACCESSIBILITY.md for what is and
isn't covered.
