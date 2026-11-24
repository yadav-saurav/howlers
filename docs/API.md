# Howlers API — v1 contract

Base path: `/api/v1`. JSON everywhere. OpenAPI UI at `/swagger-ui.html` (JSON at `/v3/api-docs`).

## Conventions

**Auth.** `Authorization: Bearer <accessToken>` (JWT, 15 min). The refresh token is an
`HttpOnly; Secure(prod); SameSite=Strict` cookie named `howlers_rt`, scoped to `/api/v1/auth`,
rotated on every refresh. Reuse of a rotated refresh token revokes the whole session family.
Frontend keeps the access token **in memory only** and calls `POST /auth/refresh` on boot and on 401.
All requests use `credentials: "include"`.

**Success responses** return the DTO directly (no envelope).

**Errors** (all 4xx/5xx):
```json
{ "status": 400, "code": "VALIDATION_FAILED", "message": "Human readable",
  "fieldErrors": { "username": "must be 3-21 characters" }, "traceId": "c0ffee...", "timestamp": "..." }
```
Codes: `VALIDATION_FAILED, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, RATE_LIMITED, BAD_REQUEST, INTERNAL_ERROR`.
Every response carries `X-Trace-Id`.

**Pagination.**
- Cursor (feeds, comments, notifications, search posts): `?cursor=<opaque>&limit=25` →
  `{ "items": [...], "nextCursor": "opaque" | null }`
- Offset (directories, admin lists): `?page=0&size=20` →
  `{ "items": [...], "page": 0, "size": 20, "totalElements": 120, "totalPages": 6 }`

**Timestamps** ISO-8601 UTC strings. **IDs** are numbers (bigint).

## Shared DTOs

```ts
UserSummary   { id, username, displayName, avatarUrl|null }
CommunityRef  { id, slug, name, iconUrl|null }
CommunitySummary { id, slug, name, description, iconUrl, bannerUrl, topic: Topic|null,
                   visibility: "PUBLIC"|"RESTRICTED"|"PRIVATE", memberCount, tags: string[], isMember }
Topic { id, slug, name, description }
Flair { id, text, color }      // color = hex like "#3FB68B"
PostSummary {
  id, type: "TEXT"|"LINK"|"IMAGE"|"POLL", title, bodyPreview: string /* plain text ≤300 */,
  url|null, linkDomain|null, media: { id, url, width|null, height|null }[],
  poll: Poll|null, author: UserSummary|null /* null = deleted account */, community: CommunityRef,
  flair: Flair|null, tags: string[], score, upvotes, downvotes, commentCount,
  myVote: -1|0|1, saved: boolean, pinned, locked, removed, createdAt, editedAt|null
}
PostDetail = PostSummary & { bodyHtml: string /* sanitized server-side */, viewer: { canEdit, canDelete, canModerate } }
Poll { multiple: boolean, closesAt|null, closed: boolean, totalVotes, myOptionIds: number[],
       options: { id, label, votes }[] }
CommentNode {
  id, postId, parentId|null, depth, body /* markdown source; render without raw HTML */,
  author: UserSummary|null, score, myVote, deleted: boolean, removed: boolean,
  createdAt, editedAt|null, replyCount, replies: CommentNode[], moreReplies: number /* not yet loaded */
}
```

## Auth `/auth`
| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/auth/register` | `{username, email, password, displayName?}` | `AuthResponse` + cookie |
| POST | `/auth/login` | `{login /*username or email*/, password}` | `AuthResponse` + cookie |
| POST | `/auth/refresh` | – (cookie) | `AuthResponse` + rotated cookie |
| POST | `/auth/logout` | – | 204, revokes current session |
| GET | `/auth/username-available?username=` | – | `{available, reason|null}` |
| POST | `/auth/verify-email` | `{token}` | `{status: "VERIFIED"\|"ALREADY_VERIFIED"}`; 400 `LINK_EXPIRED` (expired or replaced by a newer link) or `LINK_INVALID` |
| POST | `/auth/resend-verification` | – (auth) | 204; 400 if already verified; 3/hour per user |
| POST | `/auth/forgot-password` | `{email}` | 204 (always); 5/hour per IP and 3/hour per address |
| POST | `/auth/reset-password` | `{token, newPassword}` | 204, revokes all sessions |
| GET | `/auth/sessions` | – | `{id, userAgent, ip, createdAt, lastUsedAt, current, authMethod}[]` (D-041) |
| DELETE | `/auth/sessions/others` | – | `{revoked}`: sign out everywhere else (D-041) |
| DELETE | `/auth/sessions/{id}` | – | 204 |

`AuthResponse { accessToken, expiresIn, user: Me }`. Password rules: 8–128 chars, ≥1 letter and ≥1 digit.
Username: `^[A-Za-z0-9_]{3,21}$`, case-insensitive unique.
Dev mode: verification/reset emails are written to the backend log (`MAIL-DEV`) instead of sent.
Email delivery (D-038): `MAIL_MODE=log|smtp|brevo`. Emails go out after the transaction commits, on a background
thread, so these endpoints never wait for the provider and a failed send never fails the request. Verification links
last 24 hours, reset links 1 hour; both are `APP_PUBLIC_URL` + `/verify-email?token=` or `/reset-password?token=`.
`/auth/verify-email` is also limited to 30/hour per IP.

## Users
```ts
Me { id, username, email, displayName, avatarUrl, bannerUrl, bio, emailVerified, onboardingCompleted,
     role: "USER"|"ADMIN", postKarma, commentKarma, createdAt,
     settings: { theme: "dark"|"light"|"system", showNsfw, profilePublic, showCommunities, emailNotifications } }
PublicProfile { id, username, displayName, avatarUrl, bannerUrl, bio, createdAt, postKarma, commentKarma,
     followerCount, followingCount, isFollowing, isSelf, communities: CommunityRef[] /* if visible */,
     moderatorOf: CommunityRef[] }
```
| Method | Path | Notes |
|---|---|---|
| GET | `/users/me` | `Me` |
| PATCH | `/users/me` | `{displayName?, bio?}` → `Me` |
| PATCH | `/users/me/settings` | partial settings → `Me` |
| POST | `/users/me/avatar` | multipart `file` (png/jpeg/webp/gif ≤ 5 MB) → `Me` |
| POST | `/users/me/banner` | multipart `file` → `Me` |
| POST | `/users/me/password` | `{currentPassword, newPassword}` → 204 |
| POST | `/users/me/deactivate` | `{password}` → 204 (reversible by logging in) |
| DELETE | `/users/me` | `{password}` → 204 (anonymizes; content shows `author: null`) |
| GET | `/users/{username}` | `PublicProfile` (404 if deleted; limited if private) |
| GET | `/users/{username}/posts` | cursor `PostSummary` |
| GET | `/users/{username}/comments` | cursor `{comment: CommentNode, post: {id, title, community: CommunityRef}}` |
| POST / DELETE | `/users/{username}/follow` | 204 |

## Topics & onboarding
| GET | `/topics` | `Topic[]` |
|---|---|---|
| GET | `/onboarding/suggestions?topicIds=1,2` | `{communities: CommunitySummary[], users: UserSummary[]}` |
| POST | `/onboarding/complete` | `{topicIds: number[], communitySlugs: string[], followUsernames: string[], theme?}` → `Me` |

## Communities
```ts
CommunityDetail = CommunitySummary & {
  createdAt, rules: { id, title, description, position }[], flairs: Flair[],
  moderators: { user: UserSummary, role: "OWNER"|"ADMIN"|"MODERATOR" }[],
  viewer: { isMember, role: "OWNER"|"ADMIN"|"MODERATOR"|"CONTRIBUTOR"|"MEMBER"|null,
            canPost, canModerate, canView }
}
```
| Method | Path | Notes |
|---|---|---|
| GET | `/communities?q=&topic=<slug>&sort=popular\|new&page&size` | offset page of `CommunitySummary` |
| GET | `/communities/trending?limit=5` | `CommunitySummary[]` (most new members/posts last 7 days) |
| GET | `/me/communities` | `CommunitySummary[]` joined |
| POST | `/communities` | `{slug, name, description, topicId?, visibility, tags[], rules:[{title, description}]}` → `CommunityDetail` |
| GET | `/communities/{slug}` | `CommunityDetail` (PRIVATE + non-member → limited, `canView:false`) |
| PATCH | `/communities/{slug}` | mods: `{name?, description?, topicId?, visibility?, tags?}` |
| PUT | `/communities/{slug}/rules` | mods: full replacement list |
| PUT | `/communities/{slug}/flairs` | mods: `{id?, text, color}[]` |
| POST | `/communities/{slug}/icon` \| `/banner` | mods, multipart |
| POST / DELETE | `/communities/{slug}/membership` | join / leave (owner cannot leave) |
| GET | `/communities/{slug}/members?page&size` | `{user: UserSummary, role, joinedAt}` page |
| PUT | `/communities/{slug}/members/{username}/role` | owner/admin: `{role}` |

Slug: `^[a-z0-9_]{3,24}$`, unique. Restricted: anyone views, only CONTRIBUTOR+ posts. Private: members only.

## Posts & feeds
| Method | Path | Notes |
|---|---|---|
| GET | `/feed?tab=foryou\|following\|latest\|popular\|rising\|top&t=day\|week\|month\|year\|all&cursor&limit` | cursor `PostSummary`; anonymous users get `popular` for `foryou`/`following` |
| GET | `/communities/{slug}/posts?sort=hot\|new\|top\|rising&t=&cursor` | pinned posts first on first page for `hot` |
| GET | `/trending/posts?limit=5` | `PostSummary[]` |
| POST | `/posts` | `{communitySlug, type, title, bodyHtml?, url?, mediaIds?: number[], flairId?, tags?: string[], poll?: {options: string[], multiple, closesAt?}}` → `PostDetail` |
| GET | `/posts/{id}` | `PostDetail` |
| PATCH | `/posts/{id}` | author: `{title?, bodyHtml?, tags?, flairId?}` |
| DELETE | `/posts/{id}` | author (soft delete) |
| POST | `/posts/{id}/vote` | `{value: -1\|0\|1}` → `{score, upvotes, downvotes, myVote}` |
| PUT / DELETE | `/posts/{id}/save` | 204 |
| PUT / DELETE | `/posts/{id}/hide` | 204 |
| POST | `/posts/{id}/poll/vote` | `{optionIds: number[]}` → `Poll` |
| GET | `/me/saved?cursor` | cursor `PostSummary` |
| POST | `/media` | multipart `file` image ≤ 10 MB → `{id, url, width, height, contentType}` |
| GET | `/files/{key}` | serves stored media (local storage driver) |

Title 1–300 chars. Body HTML ≤ 40 000 chars after sanitization. Tags ≤ 5, `^[a-z0-9-]{1,24}$`. Poll 2–8 options.

## Comments
| Method | Path | Notes |
|---|---|---|
| GET | `/posts/{id}/comments?sort=best\|top\|new\|controversial\|old&cursor&limit=20` | cursor of top-level `CommentNode`, each with up to 2 further levels × 5 replies preloaded |
| GET | `/comments/{id}/replies?sort&cursor&limit=10` | cursor of direct children (each preloaded 1 level × 3) |
| POST | `/posts/{id}/comments` | `{body (markdown, 1–10 000), parentId?}` → `CommentNode`; max depth 10 |
| GET | `/comments/{id}` | single `CommentNode` without preloaded replies (used for live inserts) |
| PATCH | `/comments/{id}` | author `{body}` |
| DELETE | `/comments/{id}` | author; soft delete keeps tree shape |
| POST | `/comments/{id}/vote` | `{value}` → `{score, myVote}` |

`@username` mentions create notifications.

## Search
| GET | `/search?q=&type=posts\|communities\|users&community=<slug>&sort=relevance\|new\|top&t=&cursor` | `{items, nextCursor}` with item type by `type` |
|---|---|---|
| GET | `/search/suggest?q=` | `{communities: CommunityRef[], users: UserSummary[], posts: {id, title, community: CommunityRef}[]}` |

## Notifications
```ts
Notification { id, type: "POST_REPLY"|"COMMENT_REPLY"|"MENTION"|"NEW_FOLLOWER"|"POST_MILESTONE"|"COMMUNITY_ROLE",
  actor: UserSummary|null, message, link /* app route, e.g. /p/12#c-55 */, read, createdAt, count /* grouped */ }
```
| GET | `/notifications?cursor&unreadOnly=false` | cursor |
|---|---|---|
| GET | `/notifications/unread-count` | `{count}` |
| POST | `/notifications/{id}/read` | 204 |
| POST | `/notifications/read-all` | 204 |

## Ops
`GET /actuator/health` (liveness + readiness groups: db, redis, storage).

## Knowledge layer (Phase 4)

### Context Mode
| Method | Path | Notes |
|---|---|---|
| GET | `/posts/{id}/context` | `ContextView`: main claim, `stats` (analysed/total/labelled/suggested/participants/byStance), `groups` per stance (≤12 each, strongest first), `evidence` links with source comment, `openQuestions`, `agreement`, `disagreement`, `map` (≤80 nodes with parentId), `truncated` |
| PUT | `/comments/{id}/stance` | auth, `{stance: SUPPORT\|OPPOSE\|NEUTRAL\|QUESTION\|EVIDENCE\|EXPERIENCE}`; one label per user, the comment author's label counts ×2 |
| DELETE | `/comments/{id}/stance` | remove your label |

`ArgItem { commentId, parentId, excerpt, author, score, stance, suggested, myLabel, labelCounts }`. `suggested=true` means
the stance came from rules (no reader label yet). Stances are relative to what the comment replies to.

**AI stance suggestions (D-032), additive fields.** `ArgItem.stanceSource` and `MapNode.stanceSource` are
`READERS | AI | RULES` (reader labels always win; `suggested` stays `true` for AI and RULES). `stats.aiSuggested` counts
AI-suggested comments. `aiStances { state: OFF|PENDING|PARTIAL|READY, engine, suggested, remaining, note }`: a signed-in
view starts one background batch job for comments with no reader labels (≤75 per job, batches of 25, 20 jobs/hour/user,
2-minute cooldown per thread); `PENDING` means poll again. Anonymous views read stored suggestions but never start model
calls. Suggestions are cached per comment version (comment body plus the post it is judged against) and are relative to
the post's main claim. Deleted, removed and held comments are never sent or shown.

### Thread Digest
| GET | `/posts/{id}/digest?length=short\|standard\|detailed&focus=balanced\|top\|new\|beginner\|technical` | cached `DigestView` (generated on first request) |
|---|---|---|
| POST | `/posts/{id}/digest?length&focus` | regenerate (10/hour/user) |

`DigestView { engine, engineNote, generatedAt, commentCountAtGeneration, currentCommentCount, stale, sections }`;
every `Line { text, commentId, kind: DATA|ANECDOTE|OPINION|SPECULATION|STATEMENT, author }` quotes a real comment.
`consensus.status` is `CONSENSUS` only with ≥5 comments, ≥3 stance-weighted votes and ≥75% on one side.

**LLM digest (D-031), additive fields.** `engine` is `llm-v1` (written by the configured model) or `extractive-v2`.
`notice` (nullable) says why the preferred engine wasn't used, e.g. the model failed, declined, returned unverifiable
output, the hourly limit was reached (20 model digests/hour/user), or the viewer is signed out (anonymous views never call
the model). `Line` gains `commentIds` (every cited comment; `commentId` is the first) and `paraphrase` (`true` = the
model's wording, `false` = verbatim) and `kind` may be `FACT`. With `llm-v1`, `summary`/`supporting`/`opposing` are
paraphrased cited points, `takeaways` are verbatim quotes, and `sections.openQuestions: Line[]` lists unanswered
questions; `mostUseful`, `minority`, `unresolved`, `links` and `consensus` are always computed from real data.
`sections.coverage { included, total, note }` says how many comments the digest covers ("Covers 120 of 480 comments…").
Every cited id is checked against the comments sent; every quote must be a verbatim (whitespace-normalised) substring of
its comment. Cached digests drop lines whose comments were deleted, removed or held since, and are `stale` when a cited
comment was edited.

### Smart Collections (all `/me/**` require auth; collections are private by default)
| Method | Path | Notes |
|---|---|---|
| GET / POST | `/me/collections` | list / create `{name, description?}` (409 on duplicate name) |
| PATCH / DELETE | `/me/collections/{id}` | `{name?, description?, shared?}`; `shared:true` mints a share token, `false` revokes it |
| GET | `/me/collections/{id}/items?q&tag&type` | items in manual order |
| POST | `/me/collections/{id}/items` | `{type: POST\|COMMENT, postId, commentId?, note?, tags?}` (409 if already filed) |
| PUT | `/me/collections/{id}/order` | `{itemIds}` |
| PATCH / DELETE | `/me/collection-items/{itemId}` | `{note?, tags?, collectionId? (move)}` |
| GET | `/me/library/items?q&tag&type` · `/me/library/tags` · `/me/library/membership?postId&commentId` | library-wide search, tag counts, where an item is filed |
| GET | `/collections/shared/{token}` | public read-only view; items from private communities the viewer can't see are omitted |

## Community AI (Phase 5)
| Method | Path | Notes |
|---|---|---|
| GET | `/ai/status` | `{provider, model, llmConfigured, note}` (public) |
| POST | `/me/ai/ask` | `{conversationId?, question (≤1000), communitySlug?, postId?, sinceDays?}` → `{conversation, question, answer}`; 30/hour/user |
| GET | `/me/ai/conversations` · `/me/ai/conversations/{id}` | history (owner only; others get 404) |
| DELETE | `/me/ai/conversations/{id}` | delete a conversation |
| POST | `/me/ai/messages/{id}/feedback` | `{value: -1\|0\|1}` |

`MessageDto { id, role: USER|ASSISTANT, content (markdown with [n] citations), engine, intent, sources: Source[], followUps, notice, feedback, createdAt }`.
`Source { n, kind: POST|COMMENT, postId, commentId, title, communitySlug, author, score, createdAt, excerpt, link, cited }`.
Every `[n]` in `content` is guaranteed to satisfy 1 ≤ n ≤ sources.length. Citations the model invents are stripped server-side.
`postId` scopes the question to one thread, and `communitySlug` to one community. Both are permission-checked.

## Moderation (Phase 3)
All `/mod/**` and `/communities/{slug}/mod/**` endpoints require a community MODERATOR/ADMIN/OWNER (or platform admin);
everyone else gets 403. Every action is written to the moderation log.

```ts
QueueItem { targetType: "POST"|"COMMENT", postId, commentId|null, title, excerpt, author: UserSummary|null,
            removed, locked, pinned, createdAt, reportCount, reasons: {reason, count}[], details: string[] /* ≤5 newest */,
            firstReportedAt, lastReportedAt }
ModAction { id, action: "REMOVE_POST"|"APPROVE_POST"|"REMOVE_COMMENT"|"APPROVE_COMMENT"|"LOCK"|"UNLOCK"|"PIN"|"UNPIN"|"BAN"|"UNBAN",
            moderator: UserSummary|null, targetUser: UserSummary|null, postId|null, commentId|null, targetTitle|null,
            reason|null, expiresAt|null, reversed: boolean, createdAt }
Ban     { user: UserSummary, reason, expiresAt|null /* null = permanent */, bannedBy: UserSummary|null, createdAt }
Appeal  { id, action: ModAction, user: UserSummary, message, status: "PENDING"|"ACCEPTED"|"REJECTED", response|null, createdAt, resolvedAt|null }
ModStats { openReports, pendingAppeals, activeBans, actionsLast30Days, removedLast30Days }
MyModAction { action: ModAction, community: CommunityRef, appeal: Appeal|null, canAppeal: boolean }
```
| Method | Path | Notes |
|---|---|---|
| POST | `/reports` | auth. `{targetType, postId, commentId?, reason (≤100), details? (≤1000)}` → 204. 409 if you already have an open report on it. 20/hour |
| GET | `/communities/{slug}/mod/queue` | open reports grouped by target, most reported first (≤100) |
| GET | `/communities/{slug}/mod/stats` | `ModStats` |
| GET | `/communities/{slug}/mod/log?cursor` | cursor page of `ModAction`, newest first |
| GET | `/communities/{slug}/mod/bans` | active `Ban[]` |
| POST | `/communities/{slug}/mod/bans` | `{username, reason (1..300), days?: 1..3650 \| null}` → `Ban`. Can't ban yourself or a moderator of equal/higher rank |
| DELETE | `/communities/{slug}/mod/bans/{username}` | 204 |
| GET | `/communities/{slug}/mod/appeals?status=PENDING\|ALL` | `Appeal[]` |
| POST | `/mod/appeals/{id}/resolve` | `{accept: boolean, response? (≤1000)}` → `Appeal`. Accepting reverses the action (restore content / lift ban) |
| POST | `/mod/posts/{id}/{remove\|approve\|lock\|unlock\|pin\|unpin}` | `{reason?}` → `PostDetail`. Max 3 pinned per community. Remove/approve resolve the post's open reports |
| POST | `/mod/comments/{id}/{remove\|approve}` | `{reason?}` → 204 |
| GET | `/me/mod-actions/{id}` | the affected user only → `MyModAction`. `action.moderator` (and `appeal.action.moderator`) is always `null` here: decisions are the team's (D-014, D-027) |
| POST | `/me/mod-actions/{id}/appeal` | `{message (10..2000)}` → `Appeal` (with `action.moderator: null`). One appeal per action; only REMOVE_* and BAN, not reversed |
| GET | `/me/blocks` | `UserSummary[]` |
| PUT / DELETE | `/users/{username}/block` | 204. Blocked users' posts disappear from your feeds/search; their comments render as `[blocked user]`; they can't notify you |

Additions to existing DTOs: `CommunityDetail.viewer.banned: boolean`, `CommunityDetail.viewer.bannedUntil: string|null`;
`PublicProfile.isBlocked: boolean`; `CommentNode.blocked: boolean`.
Notification types added: `CONTENT_REMOVED` (link `/mod-actions/{id}`), `BANNED` (link `/mod-actions/{id}`),
`APPEAL_RESOLVED`, `PULSE_RESULT`. Banned users get 403 on post/comment/vote/pulse-vote in that community.

## Community Pulse (Phase 4)
```ts
Pulse { id, community: CommunityRef, type: "POLL"|"PREDICTION"|"OPINION"|"DEBATE", title, description,
        multiple, anonymous, allowChange, closesAt, status: "OPEN"|"CLOSED"|"RESOLVED", createdAt, author: UserSummary|null,
        totalVoters, myOptionIds: number[], resultsVisible: boolean /* true after you vote, or once closed */,
        options: { id, label, votes: number|null /* null while results hidden */, correct: boolean }[],
        resolvedOptionId|null, accuracy: number|null /* share of PREDICTION voters who picked correctly, 0..1 */,
        viewer: { canVote: boolean, canManage: boolean } }
PulseReason { author: UserSummary|null /* null when anonymous */, optionId, text, createdAt }
```
| Method | Path | Notes |
|---|---|---|
| GET | `/communities/{slug}/pulses?status=active` | `Pulse[]` open, soonest closing first |
| GET | `/communities/{slug}/pulses?status=closed&cursor` | cursor page of closed/resolved `Pulse`, newest first (history) |
| GET | `/pulses/active?limit=5` | open pulses from your joined communities (anonymous: popular public ones) |
| POST | `/communities/{slug}/pulses` | mods. `{type, title (≤200), description? (≤1000), options: string[2..8], multiple, anonymous, allowChange, durationHours: 1..720}` → `Pulse` |
| GET | `/pulses/{id}` | `Pulse` |
| POST | `/pulses/{id}/vote` | `{optionIds (1..8), reasoning? (≤500)}` → `Pulse`. 409 if already voted and `allowChange=false`; 400 if closed |
| POST | `/pulses/{id}/close` | mods → `Pulse` |
| POST | `/pulses/{id}/resolve` | mods, PREDICTION only, after close: `{optionId}` → `Pulse`; notifies voters (`PULSE_RESULT`) |
| GET | `/pulses/{id}/reasons` | `PulseReason[]` (≤50 newest; empty while results hidden) |
| DELETE | `/pulses/{id}` | mods → 204 |

Pulses close automatically at `closesAt` (status is computed on read). Live results: the UI refetches open pulses every 15 s.

## Podcasts (Phase 6)
Episodes belong to a community. Moderators of that community manage them in the studio. Only `PUBLISHED`
episodes are listed publicly, and drafts are visible to managers only. Media is **uploaded**, never fabricated: AI can
draft a cited script when `ANTHROPIC_API_KEY` is set, but audio/video generation is not configured
(`capabilities.audioGeneration=false`).

```ts
EpisodeType = "WEEKLY_ROUNDUP"|"DEEP_DIVE"|"DEBATE"|"INTERVIEW"|"BEGINNER_GUIDE"|"MONTHLY_REPORT"|"COMPARISON"|"HIGHLIGHTS"|"MULTI_PERSPECTIVE"
EpisodeStatus = "DRAFT"|"IN_REVIEW"|"PUBLISHED"|"ARCHIVED"
EpisodeSummary { id, community: CommunityRef, title, summary, type: EpisodeType, mediaKind: "AUDIO"|"VIDEO"|null,
                 durationSeconds|null, thumbnailUrl|null, status: EpisodeStatus, publishedAt|null, createdAt, playCount,
                 saved: boolean, progress: { positionSeconds, completed } | null, chapterCount,
                 audioOrigin: "UPLOADED"|"AI_TTS"|null /* null until there is media; AI_TTS = AI-generated voice (D-037) */ }
EpisodeDetail = EpisodeSummary & {
  mediaUrl|null, mediaContentType|null, allowDownload: boolean,
  chapters: { id, startSeconds, title }[],
  sources: { position, postId, commentId|null, title, communitySlug, excerpt, link }[],
  hasTranscript: boolean, transcriptVttUrl: string|null /* for <track kind="subtitles"> */,
  script: string|null /* markdown; managers only */, scriptStatus: "NONE"|"GENERATING"|"READY"|"FAILED",
  scriptSource: "HUMAN"|"AI_DRAFT"|null, scriptError: string|null,
  createdBy: UserSummary|null, publishedBy: UserSummary|null,
  viewer: { canManage: boolean },
  audioProvider: string|null, audioModel: string|null /* set only when audioOrigin = "AI_TTS", e.g. "elevenlabs", "eleven_multilingual_v2" */ }
TranscriptSegment { id, startSeconds: number, endSeconds: number, speaker: string|null, text }
Capabilities { aiScriptDrafting: boolean, audioGeneration: boolean /* a TTS provider is configured */, videoGeneration: false, maxUploadMb: number,
               audioTypes: string[], videoTypes: string[] }
```
| Method | Path | Notes |
|---|---|---|
| GET | `/podcasts/capabilities` | `Capabilities` |
| GET | `/podcasts?type=&community=<slug>&sort=recent\|popular&cursor` | cursor page of published `EpisodeSummary` |
| GET | `/podcasts/home` | `{ featured: EpisodeSummary[] (≤3, most played last 30 days), continueListening: EpisodeSummary[] (auth, in progress), recent: EpisodeSummary[] (≤12), saved: EpisodeSummary[] (auth, ≤12) }` |
| GET | `/me/podcasts/history?cursor` | episodes you've played, most recent first |
| GET | `/podcasts/episodes/{id}` | `EpisodeDetail` |
| GET | `/podcasts/episodes/{id}/transcript?q=` | `TranscriptSegment[]`; `q` filters case-insensitively |
| GET | `/podcasts/episodes/{id}/transcript.vtt` | `text/vtt` subtitles |
| PUT | `/podcasts/episodes/{id}/progress` | auth `{positionSeconds, completed?}` → 204. The first play by a user counts once in `playCount` |
| PUT / DELETE | `/podcasts/episodes/{id}/save` | 204 |
| GET | `/communities/{slug}/podcasts/studio` | managers: all episodes of the community, any status |
| POST | `/communities/{slug}/podcasts/episodes` | managers `{title (≤200), summary? (≤2000), type}` → `EpisodeDetail` (DRAFT) |
| PATCH | `/podcasts/episodes/{id}` | managers `{title?, summary?, type?, allowDownload?, script? (≤200k), chapters?: {startSeconds, title}[] (≤100), sources?: {postId, commentId?}[] (≤50)}` → `EpisodeDetail`. Editing a PUBLISHED episode's media/transcript is blocked; unpublish first |
| POST | `/podcasts/episodes/{id}/media` | managers, multipart `file` + `durationSeconds` (client-measured) → `EpisodeDetail`. Audio: mp3/m4a/ogg/wav; video: mp4/webm; validated by magic bytes; ≤ `maxUploadMb` |
| POST | `/podcasts/episodes/{id}/thumbnail` | managers, multipart image → `EpisodeDetail` |
| PUT | `/podcasts/episodes/{id}/transcript` | managers `{format: "vtt"\|"srt"\|"timestamped", content}` → `{segments: number}`. `timestamped` = lines like `[00:01:23] Speaker: text` |
| POST | `/podcasts/episodes/{id}/submit` | DRAFT → IN_REVIEW |
| POST | `/podcasts/episodes/{id}/publish` | managers; requires IN_REVIEW, media and transcript → PUBLISHED (records `publishedBy`) |
| POST | `/podcasts/episodes/{id}/unpublish` | PUBLISHED → ARCHIVED (hidden; can be resubmitted) |
| DELETE | `/podcasts/episodes/{id}` | managers; deletes episode and its media |
| POST | `/podcasts/episodes/{id}/draft-script` | managers `{topic (≤300), targetMinutes: 10..90}` → 202 `EpisodeDetail` with `scriptStatus: "GENERATING"`. Runs in the background; poll the episode. 400 when AI isn't configured. 5/day/user |

Media files are served from `/api/v1/files/**` with HTTP Range support (seeking). Their URLs are unguessable, but they are
not access-controlled, so episodes in private communities have the same limit as images (see DECISIONS).

### Podcast audio generation (D-036, D-037)
Managers can turn the saved two-host script into the episode's audio with a text-to-speech provider (ElevenLabs when
`ELEVENLABS_API_KEY` is set on the server; otherwise `capabilities.audioGeneration=false` and starting returns 400). Only
`HOST A:` / `HOST B:` lines are read (title, summary, outline, notes and the source list are skipped; citations, links and
Markdown are stripped). The result replaces the episode's media, transcript (one segment per synthesised chunk, timed from
the measured MP3 duration) and, when the script has `##` sections, its chapters. The episode then has
`audioOrigin: "AI_TTS"` with `audioProvider`/`audioModel`, and the UI labels it "AI-generated voice". Uploading media later
sets `audioOrigin` back to `UPLOADED`. There is no RSS feed yet; when one is added it must carry the same disclosure.

```ts
AudioJobStatus = "QUEUED"|"GENERATING"|"READY"|"FAILED"|"CANCELLED"
AudioJob { id, status: AudioJobStatus, provider, model, characters /* reserved for the budget */, charactersSent,
           chunksTotal, chunksDone, durationSeconds: number|null, error: string|null, cancelRequested: boolean,
           createdAt, startedAt|null, finishedAt|null }
AudioGeneration { available: boolean, provider: string|null, providerLabel: string|null, model: string|null,
                  voices: { speaker: "HOST A"|"HOST B", voiceId, name }[],
                  estimate: { characters, chunks, turns, sections, perRequestLimit, maxCharacters } /* from the saved script */,
                  budget: { dailyLimit, usedToday, remaining, resetsAt } /* per community, UTC day */,
                  job: AudioJob|null /* latest */, blockers: string[] /* why starting would fail; empty = can start */ }
```
| Method | Path | Notes |
|---|---|---|
| GET | `/podcasts/episodes/{id}/audio-generation` | managers (auth) → `AudioGeneration`. Poll while `job.status` is QUEUED/GENERATING |
| POST | `/podcasts/episodes/{id}/audio-generation` | managers → 202 `AudioJob`. 400 when unconfigured, published, no script / no host lines, over `maxCharacters` or over the daily budget; 409 if one is already running. 10/day/user |
| POST | `/podcasts/episodes/{id}/audio-generation/cancel` | managers → `AudioJob` with `cancelRequested: true`; a queued job is CANCELLED at once, a running one stops at the next chunk (the in-flight request is interrupted). 400 when nothing is running |

While a job runs, `POST …/media` answers 409. Failed and cancelled jobs count only `charactersSent` against the budget.

## Direct messages (one-to-one)
All endpoints require sign-in. You can't message someone you've blocked, or who has blocked you, and the recipient's
`dmPolicy` setting applies: `EVERYONE` (default), `FOLLOWING` (only people they follow) or `NOBODY`.

```ts
DmMessage { id, conversationId, senderId, body: string /* plain text, ≤4000; "" when deleted */,
            media: { id, url, width|null, height|null }[], createdAt, deleted: boolean }
ConversationSummary { id, other: UserSummary|null /* null if their account was deleted */,
            lastMessage: { id, senderId, excerpt, createdAt, deleted } | null,
            unreadCount, otherLastReadMessageId: number|null /* for "Seen" receipts */,
            online: boolean, canMessage: boolean, cannotMessageReason: string|null, updatedAt }
```
| Method | Path | Notes |
|---|---|---|
| GET | `/me/conversations?q=` | your conversations, most recent first. `q` matches the other username/display name or message text |
| POST | `/me/conversations` | `{username}` → `ConversationSummary` (get-or-create). 403 when blocked or not allowed by their `dmPolicy`; 400 for yourself |
| GET | `/me/conversations/{id}` | `ConversationSummary` |
| GET | `/me/conversations/{id}/messages?before=<messageId>&limit=30` | `{items: DmMessage[] /* oldest → newest */, hasMore}` |
| POST | `/me/conversations/{id}/messages` | `{body?, mediaIds?: number[] (≤4, your own uploads from POST /media)}` → `DmMessage`. Needs body or media. 60/min |
| POST | `/me/conversations/{id}/read` | `{messageId}` → 204 (marks everything up to it read; sends a READ receipt) |
| DELETE | `/me/messages/{id}` | your own message → 204 (soft delete, shown as "Message deleted") |
| GET | `/me/messages/unread-count` | `{count}` = unread messages across conversations |

`Me.settings` gains `dmPolicy`; `PATCH /users/me/settings` accepts `{dmPolicy}`. `PublicProfile` gains `canMessage: boolean`.

## Real-time (WebSocket + STOMP)
Endpoint: `ws(s)://<host>/ws` (plain WebSocket, STOMP 1.2). Send the access token in the CONNECT frame:
`Authorization: Bearer <accessToken>`. Invalid or expired tokens are rejected, so reconnect with a fresh token after refresh.
REST stays the source of truth. Events tell the client what changed, and the client updates its cache or refetches.

**Personal events**: subscribe to `/user/queue/events`. Every frame is `{ type, payload }`:
| type | payload |
|---|---|
| `MESSAGE` | `DmMessage` (also echoed to your own other tabs) |
| `MESSAGE_DELETED` | `{conversationId, messageId}` |
| `READ` | `{conversationId, userId, messageId}` |
| `TYPING` | `{conversationId, userId}` (the UI shows "typing…" for ~5 s after the last one) |
| `PRESENCE` | `{userId, online}` (only for people you have a conversation with) |
| `NOTIFICATION` | `{unreadCount}` (refetch the inbox) |
| `UNREAD_MESSAGES` | `{count}` |

**Send typing**: `SEND /app/typing` with body `{conversationId}` (rate-limited and ignored unless you're a participant).

**Topics** (each SUBSCRIBE is permission-checked, and unauthorized ones are rejected with an ERROR frame):
| destination | who may subscribe | frame body |
|---|---|---|
| `/topic/pulse.{pulseId}` | anyone who can view the pulse | `{pulseId, totalVoters}` (refetch the pulse; tallies aren't pushed so hidden results stay hidden) |
| `/topic/modqueue.{communityId}` | that community's moderators | `{communityId}` (refetch queue/stats) |

**Live feeds and threads** (signed-in clients only, since the socket needs a token):
| destination | who may subscribe | frame body |
|---|---|---|
| `/topic/posts` | anyone signed in | `NewPost` for posts in PUBLIC and RESTRICTED communities |
| `/topic/community.{communityId}` | anyone who can view that community (members only for PRIVATE) | `NewPost` for that community |
| `/topic/post.{postId}` | anyone who can view the post | `PostEvent` (below) |

```ts
NewPost { type: "POST_CREATED", postId, communityId, communitySlug, authorId, createdAt }
PostEvent =
  | { type: "COMMENT_CREATED", postId, commentId, parentId: number|null, authorId }
  | { type: "POST_STATS", postId, score, commentCount }            // batched, at most every ~2 s per post
  | { type: "COMMENT_SCORES", postId, scores: { [commentId]: number } } // batched, at most every ~2 s per post
```
Use `GET /comments/{id}` → `CommentNode` (no preloaded replies; same visibility rules as the thread, 404 if deleted or not
visible) to fetch a comment announced by COMMENT_CREATED. Events never include post or comment content, only ids and
counts, so a client can't see anything it couldn't already fetch. Block filtering happens on fetch: a blocked author's
comment comes back with `blocked: true`.

Presence and the simple broker are per-instance (single-node deployment). See DECISIONS for the multi-node path.

## Moderator funding (Phase 8, demo mode)
**Every amount is a `Money`**: `{ amountMinor: number /* integer minor units, never a float */, currency: "USD"|"EUR"|"GBP"|"JPY",
demo: boolean, label: "DEMO, no real money" | null }`. Clients must show `label` (or a DEMO badge) next to every amount where `demo`.
Only the demo provider exists (D-022): charges always succeed, are marked `demo = true` and move no money.

```ts
FundingStatus { enabled, provider: "demo"|null, demo, label|null, notice|null, currencies: { code, exponent }[] }
Policy { policy: "EQUAL"|"ACTIVITY"|"MANUAL", activityCap, updatedBy: UserSummary|null, updatedAt|null }   // default EQUAL, cap 50
PoolSummary { id, community: CommunityRef, title, purpose, goal: Money, raised: Money, contributorCount, periodStart, periodEnd,
              status: "OPEN"|"AWAITING_ALLOCATION"|"CLOSED", provider, demo, createdBy: UserSummary|null,
              closedPolicy|null, closedAt|null, createdAt }
PoolRef { id, title, community: CommunityRef, status }
Contribution { id, pool: PoolRef, kind: "ONE_TIME"|"RECURRING", amount: Money, showName, subscriptionId|null, cycle|null, chargeId, createdAt }
Subscription { id, pool: PoolRef, amount: Money, showName, status: "ACTIVE"|"CANCELLED"|"ENDED", cyclesCharged, nextChargeAt|null,
               createdAt, cancelledAt|null, cancelledBy: "USER"|"PROVIDER"|"SYSTEM"|null }
Allocation { moderator: UserSummary|null, policy, amount: Money, activityScore|null, reportsResolved|null, appealsHandled|null,
             note|null, createdAt|null }
LedgerEntry { id, entryType: "CONTRIBUTION"|"ALLOCATION", debitAccount, debitLabel, creditAccount, creditLabel, amount: Money, createdAt }
  // accounts: "contributors" -> "pool:{id}" -> "moderator:{userId}". debitLabel names a contributor only if they opted in.
TransparencyReport { pool: PoolSummary, raised, contributionCount, contributorCount, anonymousContributors,
                     namedContributors: UserSummary[], allocated, allocations: Allocation[], ledger: LedgerEntry[] /* <= 1000 */,
                     ledgerTruncated, closedNote|null,
                     reconciliation: { contributionsTotal, ledgerIn, ledgerOut, allocationsTotal, poolBalance, reconciles } }
```

| Method | Path | Who | Body / notes | Returns |
|---|---|---|---|---|
| GET | `/funding/status` | anyone | | `FundingStatus` |
| GET | `/communities/{slug}/funding` | viewers | pools (open first, ≤50), policy, viewer flags | `{status, policy, pools, viewer: {canContribute, canCreatePool, canSetPolicy, canClose}}` |
| POST | `/communities/{slug}/funding/pools` | moderators | `{title ≤120, purpose 10–2000, currency, goalMinor, periodStart?, periodEnd}`; period 1–366 days; ≤3 open pools | 201 `PoolSummary` |
| PUT | `/communities/{slug}/funding/policy` | owner (or platform admin) | `{policy, activityCap? 1–1000}` | `Policy` |
| POST | `/funding/pools/{id}/contributions` | members who aren't banned | `{amountMinor, recurring, showName}`; 1–10,000 major units; recurring charges the first month now | 201 `{contribution, subscription\|null}` |
| GET | `/funding/pools/{id}/report` | viewers | | `TransparencyReport` |
| GET | `/funding/pools/{id}/allocation-preview` | moderators | what closing now would allocate; nothing is written | `{policy, total, allocations, note, windowStart, windowEnd}` |
| POST | `/funding/pools/{id}/close` | owner | `{allocations?: {username, amountMinor}[], note?}`; MANUAL requires amounts to moderators summing exactly to `raised` and a note of ≥10 chars | `TransparencyReport` |
| GET | `/me/funding` | signed in | my contributions (≤100), subscriptions, open pools in my communities | `{status, contributions, subscriptions, openPools}` |
| POST | `/me/funding/subscriptions/{id}/cancel` | the contributor | idempotent; past charges stay | `Subscription` |
| POST | `/funding/webhooks/{provider}` | provider (no JWT) | raw JSON `{id, type, data}` + header `Howlers-Demo-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + body)>` | `{status: "processed"\|"duplicate", eventId}` |

- **Jobs** (every 60 s; `FUNDING_JOBS_ENABLED`): charge subscriptions whose `nextChargeAt` passed (one cycle per run;
  `(subscription, cycle)` is unique; missed months are skipped, never back-charged) and close pools whose period ended
  (MANUAL pools move to `AWAITING_ALLOCATION` for the owner).
- **Webhooks**: the signature is compared in constant time, the timestamp must be within 300 s, and each event id is applied once
  (`funding_webhook_events`); a replay returns 200 `duplicate`. Handled types: `subscription.cancelled {subscriptionRef}` and
  `charge.succeeded {chargeId}` (acknowledged). Other types are recorded and ignored. Bad signature → 400, unknown provider → 404.
- **Disabled** (`FUNDING_ENABLED=false`): reads work and report `enabled: false`; writes return 503 with code `FUNDING_DISABLED`.

### Moderator funding with Stripe (D-034, D-035)
`FUNDING_PROVIDER=stripe` swaps the demo provider for Stripe hosted Checkout (test mode unless live mode is explicitly
acknowledged). Everything above still applies, with these additions (all additive fields; the demo provider fills them too):

```ts
Money { amountMinor, currency, demo /* true when no real money moved: demo and test */, mode: "demo"|"test"|"live",
        label: "DEMO, no real money" | "TEST MODE: no real money" | null /* live */ }   // label follows the row's mode
FundingStatus { ..., mode, label /* from the provider */, checkout: "instant"|"hosted"|null,
                payouts: "Allocated, not paid out: ..." }
PoolSummary { ..., mode }
Contribution { ..., provider, refunded: Money }
Subscription { ..., provider, billedBy: "howlers"|"provider", paymentFailedAt|null }   // provider-billed: nextChargeAt is null
ContributeResult { contribution|null, subscription|null, checkout: { pendingId, url, provider, mode }|null }
CheckoutStatus { pendingId, status: "PENDING"|"CONFIRMED"|"CANCELLED"|"EXPIRED"|"FAILED", reason|null, pool: PoolRef,
                 kind, amount: Money, contribution|null, subscription|null }
Reconciliation { ..., refundsTotal, shortfall /* refunded after the pool closed */ }
TransparencyReport { ..., raised /* net of refunds */, refunded, refundCount }
LedgerEntry.entryType: "CONTRIBUTION"|"ALLOCATION"|"REFUND"    // REFUND: pool:{id} -> contributors
```

| Method | Path | Who | Body / notes | Returns |
|---|---|---|---|---|
| POST | `/funding/pools/{id}/contributions` | members who aren't banned | as above. With Stripe nothing is recorded yet: a PENDING contribution and a Checkout Session (payment mode, or subscription mode with an inline monthly `price_data`) are created; ≤10 unfinished checkouts per user per hour | 201 `{contribution: null, subscription: null, checkout}`; 502 `PAYMENT_PROVIDER_ERROR` if Stripe fails |
| POST | `/funding/checkouts/confirm` | the contributor | `{sessionId}` from the success URL. The server retrieves the session from Stripe and confirms it if paid; idempotent, safe to poll | `CheckoutStatus` |
| POST | `/funding/checkouts/{pendingId}/cancel` | the contributor | expires the open session at Stripe (confirms instead if it was paid after all) | `CheckoutStatus` |
| POST | `/funding/webhooks/stripe` | Stripe (no JWT) | raw event + `Stripe-Signature` (verified with the SDK's `Webhook.constructEvent`, 300 s tolerance; a test event to a live server or vice versa is refused) | `{status: "processed"\|"duplicate", eventId}` |

- **Return URLs**: success `{APP_PUBLIC_URL}/funding/pools/{id}?checkout=success&session_id={CHECKOUT_SESSION_ID}`, cancel
  `{APP_PUBLIC_URL}/funding/pools/{id}?checkout=cancelled&pending={pendingId}`. Session metadata (also copied to the
  PaymentIntent or subscription): `howlers_pool_id`, `howlers_contributor_id`, `howlers_contribution_id` (the pending id, also the
  `client_reference_id`), `howlers_anonymous`, `howlers_kind`.
- **Webhook events**: `checkout.session.completed` / `async_payment_succeeded` (confirm), `async_payment_failed` and `expired`
  (nothing recorded), `invoice.paid` (each paid invoice is one RECURRING contribution, recorded once by invoice id; the first one
  may arrive before the session event), `invoice.payment_failed` (sets `paymentFailedAt`; Stripe retries),
  `customer.subscription.deleted` (CANCELLED by PROVIDER), `charge.refunded` (cumulative `amount_refunded` becomes REFUND rows,
  never more than the contribution). Other types are recorded and ignored. Event ids go into `funding_webhook_events` in the
  same transaction as their effect; an error returns 500 so Stripe retries.
- **Recurring with Stripe**: Stripe bills each month; the demo charging job never touches Stripe subscriptions. Cancel calls
  Stripe's subscription cancel. Closing a pool cancels its Stripe subscriptions; a payment that arrives for a closed pool is
  not added (the pending row is FAILED with a reason) and has to be refunded from the Stripe dashboard.
## Files & share links (Phase 7)
Private file library. Everything under `/me/files` requires auth and only ever returns the caller's own files (other
people's ids answer 404). Every file response is `Content-Disposition: attachment` with `X-Content-Type-Options: nosniff`,
`Content-Security-Policy: default-src 'none'; sandbox` and `Cache-Control: private, no-store`. Bytes live under a private
storage prefix that the public `/files/**` media path refuses to serve.

```ts
FileItem { id, name, contentType /* from magic bytes; E2EE = application/octet-stream */, sizeBytes, e2ee,
           scanStatus: "CLEAN"|"UNSCANNED" /* CLEAN = passed structural checks, not an antivirus verdict */,
           scanDetail|null, sha256, activeLinks, createdAt, updatedAt }
FileUsage { usedBytes, quotaBytes, maxFileBytes, fileCount }
ShareLink { id, fileId, tokenHint /* last 4 chars */, status: "ACTIVE"|"EXPIRED"|"REVOKED"|"EXHAUSTED",
            createdAt, expiresAt|null, hasPassword, maxDownloads|null, downloadCount,
            keyMode: "FRAGMENT"|"PASSPHRASE"|null, revokedAt|null, lastAccessAt|null }
WrappedKey { wrappedKey /* b64url: IV(12)+AES-GCM(key)+tag */, salt /* b64url, 16-64 bytes */, iterations /* >= 600000 */ }
AccessEntry { id, at, event: "VIEWED"|"DOWNLOADED"|"WRONG_PASSWORD"|"LOCKED"|"EXPIRED"|"REVOKED"|"LIMIT_REACHED"|"FILE_DELETED",
              viewer: UserSummary|null /* null = anonymous */, visitor: string|null /* 12-hex daily salted hash, never an IP */ }
ShareMeta { fileName|null, contentType|null /* both null for E2EE */, sizeBytes, e2ee, keyMode|null, expiresAt|null,
            requiresPassword, downloadsRemaining|null, scanStatus }
```

| Method | Path | Notes |
|---|---|---|
| GET | `/me/files?q&cursor&limit` | `CursorPage<FileItem>`, newest first; `q` = case-insensitive name search |
| GET | `/me/files/usage` | `FileUsage` (quota `howlers.files.quota-mb`, default 1024; max file `max-file-mb`, default 500) |
| POST | `/me/files` (multipart `file`, `e2ee?`, `label?`) | 201 `FileItem`. Streamed to storage. 400 `fieldErrors.file` for: over size/quota, blocked executable/script/macro type, content that doesn't match the extension, unsupported type, archive with executables or bomb-like expansion. With `e2ee=true` the body must be a Howlers container (`STRATAE2` v1); the client's file name is not stored (`label` or "Encrypted file") |
| GET | `/me/files/{id}` | `FileItem` |
| PATCH | `/me/files/{id}` `{name}` | Rename (plain files keep their extension; E2EE renames the label) |
| DELETE | `/me/files/{id}` | 204. Soft-delete, revoke links, remove stored bytes after commit |
| GET | `/me/files/{id}/content` | Owner download (Bearer). Supports `Range` (206) |
| POST | `/me/files/{id}/download-url` | `{url, expiresAt}`: signed `/downloads/{grant}` valid 5 min, for plain browser downloads |
| GET | `/me/files/{id}/links` | `ShareLink[]` |
| POST | `/me/files/{id}/links` `{expiresAt?, password? (4-128), maxDownloads? (1-10000), keyMode?, wrappedKey?}` | 201 `{link: ShareLink, token}`. The token is returned **once** (only its SHA-256 is stored); share page `/s/{token}`. `keyMode` is required for E2EE files (`PASSPHRASE` requires `wrappedKey`) and rejected for plain files. `expiresAt` must be at least 1 min ahead and at most 1 year |
| DELETE | `/me/files/{id}/links/{linkId}` | 204, revoke (idempotent) |
| GET | `/me/files/{id}/links/{linkId}/access?cursor&limit` | `CursorPage<AccessEntry>`, newest first |
| GET | `/share/{token}` | Public `ShareMeta` (logs one VIEWED per visitor per 10 min). 404 unknown; **410 `GONE`** with a message when expired, revoked, exhausted or the file was deleted (logged) |
| POST | `/share/{token}/unlock` `{password?}` | Public. `{downloadUrl, expiresAt, wrappedKey: WrappedKey|null}`. Wrong password → 400 `fieldErrors.password` (logged). 5 wrong passwords per visitor per 15 min (or 50 per link per hour) → 429 (LOCKED logged). A signed-in caller is recorded as the viewer |
| GET | `/downloads/{grant}` | Public, signed grant (HMAC, 5 min). Re-checks the link at use time. Unlimited links support `Range`; a request without Range or from byte 0 counts as a download. Download-limited links ignore `Range` and send the whole file, each request counting. 404 bad/expired grant, 410 link no longer usable |

Error code `GONE` (410) is new with this section.
## Topic reputation and moderator recognition (Phases 3 and 9)
Both are derived from real rows (votes, reports, moderation actions, appeals) by background jobs; see D-020 for the rules.
A vote only queues its author for a recompute, so reputation can lag a vote by a few seconds.

```ts
ReputationTier = "NONE"|"CONTRIBUTOR"|"TRUSTED"|"EXPERT"|"AUTHORITY"
ReputationPolicy { upvote, downvote, pairDailyCap, topicDailyCap, newAccountDays, newAccountWeight, minKarma, lowKarmaWeight,
                   qualitySaturation, tiers: {tier, label, minPoints}[], topRank, topMinPoints }   // the live constants
LeaderEntry   { rank, user: UserSummary, points /* 1 decimal */, tier, topContributor, upvotes, downvotes /* counted */ }
TopicLeaderboard { topic: Topic, entries: LeaderEntry[], contributors, computedAt|null, policy: ReputationPolicy }
ReputationOverview { topics: { topic: Topic, contributors, leaders: LeaderEntry[] /* top 3 */ }[], computedAt|null }
UserReputation { hidden /* private profile, not you */, topics: { topic, points, tier, rank, topContributor, upvotes, downvotes }[], computedAt|null }
PostExpertise  { topics: Topic[], authors: { userId, topicSlug, topicName, points, tier, topContributor }[] }

ModeratorScore { moderator: UserSummary, community: CommunityRef, period: "YYYY-MM", rank|null, score /* 0..100 */, ranked,
                 components: { consistency, responsiveness, appealQuality, health, peer } /* each 0..1, or null = no data */,
                 inputs: { decisions, decisionsReversed, reportsHandled, medianResponseMinutes|null, appealsUpheld,
                           appealsOverturned, endorsements, coModerators, communityReports, communityReportsResolved,
                           removalRate|null, prevRemovalRate|null },
                 badges: { code, label, rule }[], computedAt }
ScoreFormula   { weights: { component, label, weight, definition }[], minActivity, responseHalfHours, peerFullAt, badges, note }
ModLeaderboard { period, periods: string[] /* newest first */, community: CommunityRef|null, entries: ModeratorScore[],
                 formula: ScoreFormula, computedAt|null }
ModerationRecord { hidden, optedOut /* only ever true for yourself */, entries: ModeratorScore[] /* last 24 rows */, formula }
ModPeers { period, community: CommunityRef, peers: { moderator: UserSummary, endorsedByMe, note|null, endorsementsThisPeriod }[] }
```
| Method | Path | Notes |
|---|---|---|
| GET | `/topics/{slug}/leaders?limit=25` | `TopicLeaderboard` (≤100). Lists members with ≥ 1 point; 404 for an unknown topic |
| GET | `/reputation/topics` | `ReputationOverview`: every topic with its top 3 |
| GET | `/reputation/policy` | `ReputationPolicy` |
| GET | `/users/{username}/reputation` | `UserReputation`, highest first. `hidden: true` for a private profile unless it's yours |
| GET | `/posts/{id}/expertise` | `PostExpertise` for the post author and every commenter: their best topic of this post where they are Trusted+ or a top contributor. One call per thread page; same visibility as the post |
| POST | `/admin/reputation/rebuild` | ADMIN. Full rebuild now → `{authors, rows}` (also runs at startup and nightly at 03:40 UTC) |
| GET | `/moderators/leaderboard?period=YYYY-MM&community=slug&limit=50` | `ModLeaderboard`. Default period = current UTC month. Without `community`: all public and restricted communities. Only rows with activity ≥ `minActivity` whose moderator hasn't opted out. 400 `period` if malformed |
| GET | `/moderators/formula` | `ScoreFormula` |
| GET | `/users/{username}/moderation-record` | `ModerationRecord`. `hidden: true` for others when the moderator opted out or the profile is private; private communities only for yourself |
| GET | `/communities/{slug}/moderators/peers` | auth; community moderators only (403 otherwise). Co-moderators with your endorsement for the current month |
| POST | `/communities/{slug}/moderators/{username}/endorsement` | auth; `{note? (≤280)}` → 204. Co-moderators only, not yourself; one per endorser → moderator → month (posting again edits the note). Recomputes that community's scores. 30/hour |
| DELETE | `/communities/{slug}/moderators/{username}/endorsement` | 204; 404 if you hadn't endorsed them this month |
| GET / PUT | `/me/moderator-settings` | `{publicRanking: boolean}`. Off = scores are still computed, but hidden from every leaderboard and from other people's view of your profile |
## Admin, mutes and spam holds (Phase 3, D-021)

### Admin (`/admin/**`, ADMIN role only; anonymous → 401, other users → 403)
Every state change writes one row to the append-only admin audit log.
| Method | Path | Notes |
|---|---|---|
| GET | `/admin/overview` | `Overview {users, newUsers7d, suspendedUsers, communities, quarantinedCommunities, bannedCommunities, posts, comments, openReports, openFlags, signups: DayCount[14]}`; `DayCount {day: "YYYY-MM-DD" (UTC), count}`, zero-filled |
| GET | `/admin/users?q=&status=&cursor=&limit=` | `CursorPage<AdminUser>`, newest first. `q` = username or email prefix; `status` = `ACTIVE`, `SUSPENDED`, `DEACTIVATED`, `DELETED` or `ADMIN` (role) |
| GET | `/admin/users/{id}` | `AdminUserDetail {user: AdminUser, moderatorOf: CommunityRef[], history: AuditEntry[]}` |
| POST | `/admin/users/{id}/suspend` | `{reason}` (required) → `AdminUserDetail`. Revokes every session; the account's live access tokens stop working on the next request. Not yourself, not an admin (demote first) |
| POST | `/admin/users/{id}/unsuspend` | `{reason?}` → `AdminUserDetail` |
| POST | `/admin/users/{id}/role` | `{role: "ADMIN"|"USER", reason?}` → `AdminUserDetail`. Never your own account; active accounts only; applies on the user's next request |
| GET | `/admin/communities?q=&status=&cursor=&limit=` | `CursorPage<AdminCommunity>`; `q` matches slug or name; `status` = `ACTIVE`, `QUARANTINED`, `BANNED` |
| POST | `/admin/communities/{slug}/status` | `{status, reason}` (reason required unless restoring to `ACTIVE`) → `AdminCommunity`. Moderators get a `COMMUNITY_STATUS` notification |
| GET | `/admin/flags?status=OPEN|ALL&cursor=` | `CursorPage<SpamFlag>` across all communities; `ALL` includes rejections and resolved flags |
| POST | `/admin/flags/{id}/approve` · `/admin/flags/{id}/remove` | → `FlagResolution {flagId, communityId, status}` (also audited) |
| GET | `/admin/reports` | `AdminReport[]`: open reports grouped per target across communities, most-reported first (top 100). Act with the moderator endpoints; admins moderate everywhere |
| GET | `/admin/audit?cursor=` | `CursorPage<AuditEntry>`, newest first |

```ts
AdminUser { id, username, displayName, avatarUrl, email, role: "USER"|"ADMIN", status: "ACTIVE"|"DEACTIVATED"|"DELETED"|"SUSPENDED",
            emailVerified, createdAt, postKarma, commentKarma, postCount, commentCount, suspendedReason, suspendedAt }
AdminCommunity { id, slug, name, iconUrl, visibility, status: "ACTIVE"|"QUARANTINED"|"BANNED", statusReason, memberCount, postCount,
                 createdAt, owner: UserSummary|null, openReports, openFlags }
AuditEntry { id, action, admin: UserSummary|null, targetUser: UserSummary|null, targetCommunity: CommunityRef|null, flagId, reason, createdAt }
// action: SUSPEND_USER | UNSUSPEND_USER | PROMOTE_ADMIN | DEMOTE_ADMIN | QUARANTINE_COMMUNITY | BAN_COMMUNITY | RESTORE_COMMUNITY | APPROVE_FLAG | REMOVE_FLAG
AdminReport { community: CommunityRef, targetType, postId, commentId, title, excerpt, author, reportCount, reasons: string[], removed, lastReportedAt }
```

**Account status on every request.** A valid access token is no longer enough: the request filter checks the account's
status and role (cached for 30 s, evicted immediately by admin actions). Suspended, deactivated or deleted accounts get
`401` (`"This account is suspended"` for suspensions), so the client's refresh also fails and it signs out. Login of a
suspended account returns `403 "This account is suspended"`.

**Community status.** `CommunityDetail` gains `status` and `statusReason`. `QUARANTINED`: left out of the Popular/All
tabs (latest, rising, top, popular), trending, the For You topic fallback, site-wide search and suggestions, the
directory and trending communities; still reachable by link and searchable inside the community. `BANNED`: the community
and its posts return `403 "This community has been banned"` for everyone but admins, its posts leave every feed, and
nobody can post or comment.

### Spam holds (community moderators)
Posts and comments that trip a write-time heuristic are **held**: saved with `pending: true` (and `removed: true`
internally), visible only to the author and the community's moderators, without notifications, counters or live
events until a moderator approves them. Clearly abusive content (far past a threshold) is refused with
`422 SPAM_REJECTED`; the refusal is still recorded as a closed `SpamFlag` with `outcome: "REJECTED"`.
| Method | Path | Notes |
|---|---|---|
| GET | `/communities/{slug}/mod/held` | moderators only → `SpamFlag[]` (open holds, newest first) |
| POST | `/mod/flags/{id}/approve` | releases the content (counters, reply/mention notifications and live events happen now); logs `RELEASE_HELD` in the mod log |
| POST | `/mod/flags/{id}/remove` | keeps it hidden, logs a normal `REMOVE_POST`/`REMOVE_COMMENT` (appealable) and notifies the author |

The regular moderator actions also work on held content: `POST /mod/posts/{id}/approve` (or `/mod/comments/{id}/approve`)
releases it, `remove` removes it, and both close its flags. `ModStats` gains `heldForReview`.
```ts
SpamFlag { id, community: CommunityRef, author: UserSummary|null, authorJoinedAt, targetType: "POST"|"COMMENT", postId, commentId,
           postTitle, rules: ("LINK_VELOCITY"|"DUPLICATE"|"BURST"|"MENTIONS")[], outcome: "HELD"|"REJECTED", details, excerpt,
           status: "OPEN"|"APPROVED"|"REMOVED"|"CLOSED", resolvedBy, resolvedAt, createdAt }
```
`PostSummary`/`PostDetail` gain `pending: boolean`; `CommentNode` gains `pending: boolean` and `muted: boolean`.
Thresholds live in `application.yml` under `howlers.spam.*` (see D-021).

### Mutes (private)
| Method | Path | Notes |
|---|---|---|
| GET | `/me/mutes` | `{users: UserSummary[], communities: CommunityRef[]}` |
| PUT / DELETE | `/users/{username}/mute` | 204. Their posts leave all your feeds (including community feeds), their comments arrive with `muted: true` (the client collapses them; the body is still included), and they can't notify you. They can still see and reply to you |
| PUT / DELETE | `/communities/{slug}/mute` | 204. The community leaves your Home tabs (For You, Following, Latest, Popular, Rising, Top) and trending; its own page still works |

### Feed rows carry moderator rights
`PostSummary` gains `viewer: {canModerate}`, computed for the whole page in one batched query (platform admins moderate
everywhere). `PostDetail.viewer` keeps its wider shape `{canEdit, canDelete, canModerate}`.

### Onboarding skip
| POST | `/onboarding/skip` | → `Me` with `onboardingCompleted: true`; nothing else changes. The guided setup isn't offered again |

## Infrastructure changes (Phase 10, D-023 to D-026)

### Image variants
```ts
Thumbnails { w320: string|null, w960: string|null }   // resized copies; null = not made (small, GIF, WebP, older uploads)
```
- `POST /media` → `{id, url, width, height, contentType, thumbnails: Thumbnails|null}`.
- `PostSummary.media[]` / `PostDetail.media[]` (`MediaRef`) gain `thumbnails: Thumbnails|null`.
- `GET /files/{key}?w=320|960` serves the variant when it exists, otherwise the original (same headers, Range works).
  Use it for plain image URLs such as `avatarUrl`, `bannerUrl` and `iconUrl`. Any other `w` value is ignored; a
  non-numeric one is a 400. `private/` keys are never served here (404).

### Private DM images
| Method | Path | Notes |
|---|---|---|
| POST | `/me/dm-media` | multipart `file` (PNG/JPEG/WebP/GIF, ≤10 MB, 60/h) → 201 `DmMedia`. Stored under `private/`; attach it with `mediaIds` when sending |
| GET | `/dm-media/{grant}?w=320\|960` | public route (an `<img>` can't send the token). The grant names the image and the reader; 404 unless the reader uploaded it or is a participant of a conversation where it's attached to a message that isn't deleted. `Cache-Control: private, max-age=<seconds until expiry>`, nosniff, `default-src 'none'` |

```ts
DmMedia { id, url, width|null, height|null, thumbnails: Thumbnails|null,
          signed: boolean,          // true: participant-only URL that expires; false: legacy public URL
          expiresAt: string|null }  // 30-60 min ahead; refetch the messages for fresh URLs
```
`DmMessage.media` is now `DmMedia[]`. URLs are signed for the requesting participant, so the realtime `MESSAGE` event
is built per recipient. Images sent before this change keep their public `/files/media/...` URL (`signed: false`).
`POST /me/conversations/{id}/messages` still accepts your public uploads from `POST /media`; DM images can't be used in
posts (`POST /posts` → 400 on a `private/` media id). DM grants aren't accepted by `/downloads/{grant}`.

### Feeds
No contract change. First pages of Popular/Latest/Rising/Top and of public community feeds may be up to 30 s old in
**order** (new posts appear within the TTL); all per-viewer fields, scores and counts are always fresh (D-025).

### Demo data notice
| GET | `/demo/status` | public → `{demoData: boolean, message: string|null}`. `true` when the database contains the fictional demo accounts (the UI shows a dismissible notice) |

### Operations
| Path | Port | Notes |
|---|---|---|
| `/livez`, `/readyz` | main (8080) | liveness/readiness probes, public, no details |
| `/actuator/health`, `/actuator/info` | main outside prod; management (8081) in prod | |
| `/actuator/prometheus` | management port (8081) in prod only | never published by the proxy; not exposed outside prod |

## Privacy and safety hardening (D-028 to D-030)

### Image metadata
No contract change. Every image upload (`POST /media`, `/me/dm-media`, avatars, banners, community icons, podcast
covers) is stored without EXIF/GPS, XMP, IPTC, comments, text chunks or trailing data. A non-default EXIF orientation is
kept as a minimal orientation-only EXIF block. Pixels are untouched (lossless). A file whose structure can't be read
→ 400 `fieldErrors.file`. Private files (`POST /me/files`) are stored byte for byte, metadata included (D-028).

### Antivirus (`howlers.files.antivirus=clamav`)
- `FileItem` and `ShareMeta` gain `scanEngine: "STRUCTURAL"|"CLAMAV"`. `CLAMAV` means ClamAV scanned the file and found
  nothing. `STRUCTURAL` means type and structure checks only, which is not an antivirus scan (always the case when
  scanning is off, for files uploaded before V13, and for fail-open uploads, whose `scanDetail` says the scanner was
  unavailable).
- `POST /me/files` → 400 `fieldErrors.file` "ClamAV flagged this file (<signature>)…" when clamd reports a signature.
  While clamd is unreachable and `antivirus-required=true`: 400 with "The antivirus scanner isn't reachable…".
- Images (`POST /media`, `/me/dm-media`, profile and community images) and podcast media
  (`POST /podcasts/episodes/{id}/media`): 400 `fieldErrors.file` when flagged; 503 `ANTIVIRUS_UNAVAILABLE` while clamd
  is unreachable and scanning is required. E2EE uploads are never sent to the scanner.

### Maintenance (admin)
| Method | Path | Notes |
|---|---|---|
| GET | `/admin/maintenance` | → `{antivirus: {engine: "none"\|"clamav", required, reachable, version\|null}, legacyDmMedia: {pending, runs: MaintenanceRun[]}}` |
| POST | `/admin/maintenance/legacy-dm-media` | runs the legacy DM image move now (it also runs at startup when anything is pending) → `MaintenanceRun`. Idempotent |

```ts
MaintenanceRun { id, job: "legacy-dm-media-private", triggeredBy: number|null /* null = at startup */, startedAt,
                 finishedAt|null, candidates, moved, skipped /* key also published in a post, comment or profile */, failed,
                 detail|null }
```
After a move, the image's old `/files/media/...` URL returns 404 and messages carry participant-signed `DmMedia`
(`signed: true`) for it. `POST /me/conversations/{id}/messages` with one of your public uploads that isn't in any post
now moves it to private storage too, so the response and events carry signed URLs, and that media id can no longer go into a post.

### Real-time
- STOMP `CONNECT` is refused (ERROR frame "This account can't connect") for suspended, deactivated and deleted
  accounts, even with an unexpired access token. The session's role is read from the account.
- When an account is suspended, deactivated, deleted or has its role changed, its open WebSockets on the node are
  closed at once with close code 1008 ("Account access revoked"). Clients should treat this like a revoked session.

## Link previews, robots and sitemaps (D-039)
Public, no auth. nginx serves `/robots.txt`, `/sitemap.xml` and `/sitemaps/*.xml` from these endpoints and routes
crawler page requests to `/seo/page` (Vite does the same for robots/sitemaps in dev).

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/seo/page?path=/c/x/p/1` | `text/html`: title, description, canonical, `og:*`, `twitter:*`. The `X-Howlers-Path` header (set by nginx to the original request URI) wins over `path`. Hidden or missing content gets generic tags with `noindex`. Cached 5 min. |
| GET | `/api/v1/seo/robots.txt` | `text/plain`, with `Sitemap: {APP_PUBLIC_URL}/sitemap.xml` |
| GET | `/api/v1/seo/sitemap.xml` | sitemap index |
| GET | `/api/v1/seo/sitemaps/{kind}-{n}.xml` | `kind` = `static`, `communities`, `posts`, `podcasts`; 404 past the last page |

## Web push (D-040)
| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/push/config` | public | `{available, publicKey, unavailableReason, categories, defaultCategories}`; `publicKey` is the VAPID key for `pushManager.subscribe` |
| GET | `/api/v1/me/push/subscriptions` | user | this account's devices: `{id, label, categories, createdAt, lastUsedAt}` (endpoints are never returned) |
| POST | `/api/v1/me/push/subscriptions` | user | `{endpoint, keys: {p256dh, auth}, label?, categories?}` → device. Upsert by endpoint (refresh keeps categories when `categories` is omitted). 503 `PUSH_UNAVAILABLE` without VAPID keys; 400 for an endpoint outside the push-service allow-list or invalid keys. 20 per 10 min. |
| PATCH | `/api/v1/me/push/subscriptions/{id}` | user | `{categories?, label?}` |
| DELETE | `/api/v1/me/push/subscriptions/{id}` | user | 204 |
| POST | `/api/v1/me/push/unsubscribe` | user | `{endpoint}`: removes this browser's row (sign-out). 204 |
| POST | `/api/v1/me/push/subscriptions/{id}/test` | user | sends a test push now: `{outcome: DELIVERED|GONE|REJECTED|FAILED|UNAVAILABLE, status, message}`. DELIVERED means the push service accepted it. 5 per minute. |

Push payload (encrypted, aes128gcm): `{"title", "body", "url" (relative), "tag", "type"}`. Categories: `REPLIES`
(POST_REPLY, COMMENT_REPLY), `MENTIONS`, `MESSAGES` (DMs: sender only, no text), `MODERATION` (CONTENT_REMOVED, BANNED,
APPEAL_RESOLVED, COMMUNITY_STATUS, COMMUNITY_ROLE), `FOLLOWERS`, `ACTIVITY` (POST_MILESTONE, PULSE_RESULT).
## Account security: passkeys, two-factor authentication, sign-in alerts (D-041, D-042)

### Sign-in
| Method | Path | Body | Result |
|---|---|---|---|
| POST | `/auth/login` | `{login, password}` | `AuthResponse` + refresh cookie, **or**, when the account has 2FA on, `MfaRequired {mfaRequired: true, mfaToken, expiresIn, methods: ["totp","recovery"]}` with no cookie and no session |
| POST | `/auth/login/2fa` | `{mfaToken, code}` | `AuthResponse` + refresh cookie. `code` is a 6-digit authenticator code or a recovery code (`abcde-fghjk`; case, spaces and dashes ignored). 401 `INVALID_CODE`; 401 `MFA_TOKEN_EXPIRED` (expired after 5 min, used, unknown, or 5 wrong codes on it); 429 `MFA_LOCKED` (5 wrong codes in a row on the account lock the second step for 5 min). 30/10 min per IP, 10/10 min per token |
| POST | `/auth/passkey-login/options` | – | `{requestId, publicKey}`: `PublicKeyCredentialRequestOptions` JSON (binary fields base64url), no `allowCredentials` (usernameless), `userVerification: "required"`. Sets the `howlers_wac` cookie. Origin-checked; 30/5 min per IP |
| POST | `/auth/passkey-login/verify` | `{requestId, credential}` (`PublicKeyCredential.toJSON()` shape) | `AuthResponse` + refresh cookie, the same session as a password login. No 2FA step. 400 `CEREMONY_EXPIRED` (unknown, used, expired, or started in another browser); 401 `PASSKEY_UNKNOWN`, `PASSKEY_INVALID` (bad signature, wrong origin or rp id, no user verification), `PASSKEY_FLAGGED`; 403 suspended |

Every successful sign-in (password, password + code, passkey, registration) sets `howlers_did`, a random device id
(HttpOnly, SameSite=Strict, path `/api/v1/auth`, 400 days). Refresh does not count as a sign-in.

### Signed-in management (all require a Bearer token)
| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/auth/security` | – | `{totp: {enabled, enabledAt, recoveryCodesRemaining}, passkeys, newSignInAlerts, passkeyRpId}` |
| PATCH | `/auth/security` | `{newSignInAlerts}` | the summary |
| POST | `/auth/2fa/totp/setup` | `{password}` | `{secret (base32), otpauthUri, qrSvg}`; 400 wrong password, 409 already on. The pending secret lasts 15 min |
| POST | `/auth/2fa/totp/confirm` | `{code}` | `{recoveryCodes: string[10]}` (shown once); 401 `INVALID_CODE`, 400 `SETUP_EXPIRED` |
| POST | `/auth/2fa/recovery-codes` | `{code}` (authenticator code only) | `{recoveryCodes}`; the old set stops working |
| POST | `/auth/2fa/totp/disable` | `{password, code}` (authenticator or recovery code) | 204 |
| GET | `/auth/passkeys` | – | `{id, name, createdAt, lastUsedAt, synced, flagged, transports}[]` |
| POST | `/auth/passkeys/options` | – | `{requestId, publicKey}`: `PublicKeyCredentialCreationOptions` JSON, `residentKey: "required"`, `userVerification: "required"`, existing passkeys in `excludeCredentials`. Sets `howlers_wac` |
| POST | `/auth/passkeys` | `{requestId, credential, name?}` | 201 passkey; 400 `CEREMONY_EXPIRED` / `PASSKEY_INVALID`; 409 already registered. Blank name = "Passkey on Chrome on macOS". Max 20 |
| PATCH | `/auth/passkeys/{id}` | `{name}` (1-60) | 204 |
| DELETE | `/auth/passkeys/{id}` | – | 204 (404 for someone else's) |
| DELETE | `/auth/sessions/others` | – (needs this browser's refresh cookie) | `{revoked: n}`: signs out every other session; 400 without a cookie of this account |

`GET /auth/sessions` rows also carry `authMethod`: `PASSWORD`, `PASSWORD_TOTP`, `PASSWORD_RECOVERY`, `PASSKEY`, or
null for sessions created before V18. It is kept through refresh rotation.

Account-security mutations are limited to 30 per 10 min per user. Ceremony challenges live 5 minutes and are consumed
by the first finish attempt, whether it succeeds or not.

### New sign-in email
On a successful sign-in from a device the account hasn't used before (keyed hash of `howlers_did` + browser family +
OS family), the user gets "New sign-in to your Howlers account" with the time (UTC), "Browser on OS", the sign-in method
and a link to `APP_PUBLIC_URL/settings?tab=account#sessions`. Not sent when the account has no known device yet (the
registration sign-in, or the first sign-in of an account created before this feature), and not sent when the user
turned `newSignInAlerts` off or the server sets `NEW_SIGNIN_ALERTS=false`. No IP address or location appears in it.
## Moderator payouts with Stripe Connect (D-046, D-047)
Test mode only unless live Connect is explicitly allowed. Off unless `STRIPE_CONNECT_ENABLED=true` (with
`FUNDING_PROVIDER=stripe`); while off, Stripe pools still create payouts, which stay HELD with the reason
"Payouts aren't enabled on this server yet…". Every amount is a `Money` with its mode label. No bank or identity detail
ever appears in the API: Stripe's hosted onboarding collects it.

```ts
ConnectAccount { status: "NOT_STARTED"|"PENDING"|"REQUIREMENTS_DUE"|"ENABLED", accountHint: "acct_…1234"|null, detailsSubmitted,
                 payoutsEnabled, transfersActive, requirementsDue /* count of requirements.currently_due */, disabledReason|null,
                 message /* what to do next */, lastPayoutStatus|null /* last bank payout of the connected account */,
                 lastPayoutAt|null, syncedAt|null }
Payout { id, pool: PoolRef, amount: Money, status: "HELD"|"FAILED"|"PAID"|"REVERSED",
         label /* "Paid out (test mode)" | "Held: connect your account" | "Held: payouts not enabled yet" |
                  "Held: transfer failed, retrying" | "Reversed: held for review" | "Paid out (test mode), partly reversed" */,
         reason|null, transferred: Money /* net of reversals */, reversed: Money, paidAt|null, createdAt }
MyPayouts { connectEnabled, mode: "test"|"live"|null, label|null, notice, eligible /* moderates a community or has payouts */,
            account: ConnectAccount, payouts: Payout[], held: Money[], paid: Money[] /* per currency */ }
OnboardingLink { url /* Stripe-hosted, single use */, expiresAt /* epoch seconds */ }
AllocationPayout { status, label, paidAt|null }                       // FundingAllocation.payout, closed Stripe pools only
PayoutReconciliation { applicable, allocated, transferred, held, reversed: Money, reconciles }  // TransparencyReport.payouts
```

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/me/funding/payouts` | user | The caller's account status and payouts. |
| POST | `/me/funding/connect/onboarding` | moderator (of any community, or anyone with payouts) | Creates the caller's Express account the first time (idempotent), returns a fresh `OnboardingLink`. 403 for non-moderators, 503 `CONNECT_DISABLED` when off, 502 `PAYMENT_PROVIDER_ERROR` with Stripe's reason (e.g. Connect not activated on the platform). Stripe returns the browser to `/funding/payouts?connect=return`, or `?connect=refresh` when the link expired. |
| POST | `/me/funding/connect/refresh` | user | Re-reads the caller's account from Stripe; if it is ENABLED, held payouts are sent right after. Returns `MyPayouts`. |
| GET | `/admin/funding/stripe` | admin | `StripeStatus { fundingEnabled, provider, mode, label, secretKeySet, webhookSecretSet, connectWebhookSecretSet, lastWebhook: { type, outcome, receivedAt }\|null, webhooksLast24h, connectEnabled, connectNote\|null, connectCountry\|null, webhookUrl, platformEvents[], connectEvents[], connectedAccounts, accountsEnabled, payoutsHeld, payoutsFailed, payoutsPaid, payoutsReversed, openReviews }`. Never a secret value. |
| GET | `/admin/funding/reviews?open=true` | admin | `ReviewFlag[] { id, kind: "REFUND_AFTER_PAYOUT"\|"TRANSFER_REVERSED"\|"TRANSFER_FAILING", pool: PoolRef, payoutId\|null, moderator\|null, amount: Money, detail, createdAt, resolvedAt\|null, resolvedBy\|null, resolutionNote\|null }` |
| POST | `/admin/funding/reviews/{id}/resolve` | admin | `{ note }` (5-500 chars). Marks the flag handled and writes `RESOLVE_PAYOUT_REVIEW` to the admin audit log. |
| POST | `/admin/funding/payouts/run` | admin | Runs the payout job now: `{ attempted, paid, stillHeld, failed }`. 503 when Connect is off. |

**Webhooks.** `POST /funding/webhooks/stripe` also handles `account.updated` (status flags; releases held payouts when the
account becomes ENABLED), `transfer.created` / `transfer.updated` (records a transfer whose API response was lost, matched by
`metadata.howlers_payout_id`, destination and amount; books reversals), `transfer.reversed` (cumulative `amount_reversed` → one
PAYOUT_REVERSAL row per state, payout REVERSED when fully reversed, review flag), and `payout.paid` / `payout.failed` /
`payout.canceled` on connected accounts (the event's top-level `account`; shown as "last bank payout"). Signatures are checked
with `STRIPE_WEBHOOK_SECRET` or, if set, `STRIPE_CONNECT_WEBHOOK_SECRET`; event ids are de-duplicated as before.

**Ledger.** Two new append-only entry types: `PAYOUT` (`moderator:{id}` → `connected:{id}`, label "u/name's Stripe account
(test mode)") and `PAYOUT_REVERSAL` (back). The pool's own reconciliation is unchanged (they don't touch `pool:{id}`).
`TransparencyReport.payouts` checks allocated = transferred + held against the PAYOUT rows and the payout rows.
`FundingStatus.payouts` describes what happens to allocations (recorded only, held until payouts are enabled, or paid out
through Stripe Connect).
## Pack rooms, reactions and automod (D-043 to D-045)

### Pack rooms (group conversations)
A pack room is a conversation with a name, an optional icon emoji and 3 to 50 members at creation. It shares the
conversation endpoints above (`/me/conversations/{id}`, `/messages`, `/read`, `DELETE /me/messages/{id}`, typing, the
unread count and the participant-signed image URLs), so one-to-one conversations behave exactly as before.

```ts
ConversationSummary += { kind: "DIRECT" | "ROOM", room: RoomInfo | null }
  // ROOM: other = null, otherLastReadMessageId = null, online = "another member is online", canMessage = true,
  // lastMessage.excerpt is "mira_k: text" for other people's messages, the line itself for system messages
  // (senderId null), or "Message from someone you blocked".
RoomInfo { name, iconEmoji: string|null, memberCount, myRole: "OWNER" | "ADMIN" | "MEMBER" }
DmMessage += { kind: "USER" | "SYSTEM", fromBlocked: boolean }
  // SYSTEM: a membership or settings event rendered as text in body ("mira_k added dev_ostrander"); never unread.
  // fromBlocked (rooms only): from someone you blocked; body is "" and media is [] (show it collapsed).
RoomDetail { id, name, iconEmoji, myRole, maxMembers: 50, createdAt,
             members: { user: UserSummary, role, joinedAt, lastReadMessageId: number|null, online }[] }
             // owner first, then admins, then by join time; lastReadMessageId gives per-member receipts ("Seen by 3")
```
| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/me/rooms` | anyone | `{name ≤60, iconEmoji?, usernames: 2..49}` → 201 `ConversationSummary`. Each invitee must pass the first-message rule from you (blocks either way, then their `dmPolicy`); the first refusal names the person (400, field `usernames`). 10/h |
| GET | `/me/rooms/{id}` | members | `RoomDetail`. 404 for non-members and for one-to-one conversations |
| PATCH | `/me/rooms/{id}` | owner, admins | `{name?, iconEmoji?}` (`""` clears the icon) → `RoomDetail`; writes a system line |
| POST | `/me/rooms/{id}/members` | owner, admins | `{usernames: 1..48}` → `RoomDetail`. Same invite rule; new members start with nothing unread |
| DELETE | `/me/rooms/{id}/members/{userId}` | owner (anyone but themselves), admins (members only) | → `RoomDetail`. The removed person loses the whole room at once, including signed image URLs |
| PUT | `/me/rooms/{id}/members/{userId}/role` | owner | `{role: "ADMIN" \| "MEMBER"}` → `RoomDetail` |
| POST | `/me/rooms/{id}/transfer` | owner | `{userId}` → `RoomDetail`; the old owner becomes an admin |
| POST | `/me/rooms/{id}/leave` | members | 204. An owner who leaves hands the room to the longest-standing admin (else member); the last one out deletes it |
| DELETE | `/me/rooms/{id}` | owner | 204. Deletes the room and its history for everyone |

Blocks inside a room: a member who blocked someone still shares the room but gets their messages as `fromBlocked`, isn't
counted unread for them, doesn't get their typing, and can't react to them. Presence is no longer pushed between people
with a block either way (also for one-to-one conversations).

New personal events: `ROOM_UPDATED {conversationId}` (name, icon or members changed: refetch the room and summary),
`ROOM_REMOVED {conversationId}` (you left, were removed or the room was deleted: drop it), and
`REACTIONS {targetType: "MESSAGE", targetId, conversationId}`. `READ` receipts go to every other member.

### Reactions
A curated set (`GET /reactions/catalog` → `{key, emoji, label}[]`): `like` 👍, `heart` ❤️, `laugh` 😂, `wow` 😮,
`sad` 😢, `fire` 🔥, `howl` 🐺, `party` 🎉, `clap` 👏, `think` 🤔. One of each kind per person per target.

```ts
TargetReactions { targetType: "POST" | "COMMENT" | "MESSAGE", targetId,
                  reactions: { reaction, emoji, label, count, mine, sample: string[] /* ≤3 usernames, earliest first,
                               never people you blocked */, others: number }[] /* catalog order */ }
```
| Method | Path | Notes |
|---|---|---|
| GET | `/reactions?targetType=COMMENT&ids=1,2,3` | ≤100 ids → `TargetReactions[]` for the ids you can see (others are left out). Public for posts and comments; MESSAGE needs sign-in and membership |
| POST | `/reactions` | `{targetType, targetId, reaction, on?: boolean}` (no `on` toggles) → `{active, summary}`. Auth, 60/min. 404 for content you can't see, removed, deleted, held or system content; 403 with a block either way or a community ban |

Reacting to someone's post or comment notifies them (type `REACTION`, grouped per target; blocked and muted people never
notify you, and your own reactions don't). Live: `/topic/post.{postId}` gains
`{type: "REACTIONS", postId, targetType: "POST" | "COMMENT", targetId}`; message reactions arrive as the personal
`REACTIONS` event. Both carry ids only. Deleting a message clears its reactions.

### Automod (community moderators and platform admins)
Base path `/communities/{slug}/mod/automod`.
```ts
AutomodRule { id, name ≤80, kind: "KEYWORD" | "REGEX" | "LINK_LIMIT" | "ACCOUNT_AGE" | "KARMA" | "DOMAIN",
              pattern: string|null /* KEYWORD: one phrase per line, * = any letters; REGEX: one RE2 pattern ≤500;
                                     DOMAIN: one domain per line (subdomains included) */,
              threshold: number|null /* LINK_LIMIT 0..100 links; ACCOUNT_AGE 1..3650 days; KARMA 1..1,000,000 */,
              appliesTo: "POSTS" | "COMMENTS" | "BOTH", action: "HOLD" | "REMOVE" | "FLAG",
              reason ≤200 /* shown to the author */, enabled, createdBy, createdAt, updatedAt, hits30d }
```
| Method | Path | Notes |
|---|---|---|
| GET | `/rules` | `AutomodRule[]` |
| POST | `/rules` | rule body → 201 `AutomodRule`. Up to 50 rules; invalid patterns are 400 with field `pattern` |
| PUT | `/rules/{id}` | partial body (left-out fields keep their values) → `AutomodRule` |
| DELETE | `/rules/{id}` | 204 |
| POST | `/preview` | a draft rule (not saved) → `{scannedPosts, scannedComments, matchCount, matches: {targetType, postId, commentId, author, excerpt, detail, authorExempt, createdAt}[] /* ≤50 */, timedOut}` against the latest 200 posts and 300 comments. Changes nothing. 30 per 10 min |
| GET | `/hits?limit=50` | recent `{id, ruleId, ruleName, action, outcome, targetType, postId, commentId, author, detail, excerpt, createdAt}[]` |

At write time (new posts and comments, after the global spam checks): **REMOVE** stores the item removed and logs a
normal, appealable `REMOVE_POST`/`REMOVE_COMMENT` with no moderator and the reason `Automod: <rule>: <reason>`; the
author gets the usual removal notification with the rule's reason. **HOLD** uses the spam hold flow (Held tab,
`spam_flags.rules` contains `AUTOMOD`), logs `AUTOMOD_HOLD`, and sends the author an `AUTOMOD_HELD` notification with the
reason. **FLAG** publishes normally and logs `AUTOMOD_FLAG`. The strongest outcome wins (remove > hold > flag; a spam hold
counts as hold). Rule changes are logged as `AUTOMOD_RULE` by the moderator who made them. Moderators of the community
and admins are exempt; automod never bans. Edits to existing content are not re-checked.
