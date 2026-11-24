# Database

PostgreSQL 17, migrations in `backend/src/main/resources/db/migration` (Flyway). Hibernate runs with
`ddl-auto=validate` — it never creates tables.

## ER overview (implemented)

```
users ─┬─< refresh_tokens          (family_id = one device session)
       ├─< email_tokens            (verify / reset, hashed)
       ├─< user_topics >── topics
       ├─< user_follows >─ users
       ├─< community_members >── communities ─┬─< community_rules
       │        (role)                         ├─< community_flairs
       │                                       └── topics
       ├─< posts >── communities   posts ─┬─< post_media >── media_assets
       │                                  ├── polls ─< poll_options ─< poll_votes
       │                                  ├─< post_votes / saved_posts / hidden_posts
       │                                  └─< comments (parent_id → comments) ─< comment_votes
       └─< notifications
```

## Key decisions
- **IDs**: `bigint GENERATED ALWAYS AS IDENTITY`. Monotonic, so `NEW` sorts and keyset cursors use `id`.
- **Soft delete** on users (anonymised), posts and comments (`deleted_at`): threads keep their shape.
- **Denormalised counters** (`score`, `upvotes`, `comment_count`, `member_count`, karma, follower counts) are
  updated with relative `SET x = x + :delta` in the same transaction as the source row. The vote tables are
  the source of truth; `VoteConcurrencyIT` asserts counters always equal `SUM(value)`.
- **Ranking columns** stored, not computed per query: `posts.hot_rank`, `comments.best_rank` (Wilson),
  `comments.controversy`. Updated on every vote/comment. `hot_rank` embeds time, so it needs no cron.
- **Comments**: adjacency list + `depth` (max 10) + `reply_count`. Indexed on `(post_id, best_rank)` for
  roots and `(parent_id, best_rank)` for children; fan-out loaded with `ROW_NUMBER() OVER (PARTITION BY parent_id)`.
- **Search**: generated `tsvector` columns (title A, tags B, body C) with GIN indexes; `pg_trgm` on usernames.
  `strata_tags_text()` is an IMMUTABLE wrapper because `array_to_string` is only STABLE.
- **Notifications** grouping: partial unique index `(user_id, group_key) WHERE read_at IS NULL` + upsert.
- **Partial indexes** on posts exclude deleted/removed rows so feed indexes stay small.

## Migrations
| Version | Content |
|---|---|
| V1 | core schema (identity, communities, posts, comments, votes, media, polls, notifications) |
| V2 | reference topics |
| V3 | knowledge layer: comment stances, thread digests, collections and collection items |
| V4 | community AI: comment search vectors, AI conversations and messages |
| V5 | moderation and Community Pulse: reports, moderation actions, bans, appeals, pulses and votes |
| V6 | podcasts: shows, episodes, chapters, transcripts, sources, listening state |
| V7 | direct messages: conversations, participants, messages, message media, `users.dm_policy` |
| V8 | private files (`user_files`), share links (`file_share_links`: token hashes, optional wrapped E2EE keys), access log (`file_share_access`: hashed visitors, no IPs) |
| V9 | topic reputation (`topic_reputation`, `reputation_dirty`, `reputation_runs`) and moderator recognition (`moderator_settings`, `moderator_endorsements`, `moderator_scores`) |
| V10 | admin, mutes, spam: `communities.status`/`status_reason`, `users.suspended_reason`/`suspended_at`, `posts`/`comments` `pending` + `link_count`, `posts.content_hash`/`link_hash`, `spam_flags`, `user_mutes`, `community_mutes`, append-only `admin_actions` (trigger) |
| V11 | moderator funding: `funding_policies`, `funding_pools`, `funding_subscriptions`, `funding_contributions`, `funding_allocations`, `funding_ledger` (append-only via triggers), `funding_webhook_events` (D-022) |
| V12 | infrastructure: `media_assets.variant_widths` (image variants), index `dm_message_media (media_id)` for private DM image grants (D-023, D-024) |
| V13 | privacy hardening: `user_files.scan_engine` (STRUCTURAL / CLAMAV), `maintenance_runs` (one row per run of a one-time job such as the legacy DM image move) (D-029, D-030) |
| V14 | AI digests and stances: LLM usage records and `comment_ai_stances` (D-031 to D-033) |
| V15 | Stripe: pending contributions, provider/mode on money rows, invoice idempotency (D-034, D-035) |
| V16 | podcast audio generation: `podcast_tts_jobs`, episode audio origin (D-036, D-037) |
| V17 | pack rooms, reactions and automod: room columns on conversations/participants/messages, `reactions`, automod rules and hits (D-043 to D-045) |
| V18 | account security: `account_security`, `totp_recovery_codes`, `mfa_login_challenges`, `user_passkeys`, `webauthn_challenges`, `known_devices`, `refresh_tokens.auth_method` (D-041, D-042) |
| V19 | web push: `push_subscriptions` (one row per user device: unique `endpoint`, `p256dh`/`auth` keys, per-device `categories`, `failure_count`, `last_used_at`; cascades with the user) (D-040) |
| V20 | Stripe Connect payouts: `funding_connected_accounts` (account id and status flags only), `funding_payouts` (one per positive allocation of a Stripe pool: HELD / FAILED / PAID / REVERSED, transfer id, idempotency attempt), `funding_review_flags` (refund after payout, reversed or failing transfers), ledger entry types `PAYOUT` / `PAYOUT_REVERSAL` with `payout_id` and unique `external_ref` (D-046, D-047) |

Future phases add tables (reports, moderation_actions, collections, pulses, digests, ai_*, podcasts, files,
funding_*) as new migrations — never by editing applied ones.

## Infrastructure (V12, D-023 to D-025)
- `media_assets.variant_widths VARCHAR(40)`: which resized variants exist beside the original (`"320,960"`), NULL when
  none (older uploads, GIF, WebP, small images). Variant keys are derived: `<key>_w320.<ext>`.
- DM images uploaded through `/me/dm-media` are ordinary `media_assets` rows whose `storage_key` starts with `private/`.
  `ix_dm_message_media_media` indexes `dm_message_media (media_id)` for the per-request grant check.
- Storage bytes live in the configured driver (local directory or an S3 bucket); keys are identical in both, so moving
  between drivers is a copy (`mc mirror ./data/uploads local/howlers`), not a migration.
- The feed cache (D-025) lives only in Redis (`feed:v1:*`, 30 s TTL) and holds post ids and a cursor, nothing else.

## Privacy hardening (V13, D-028 to D-030)
- `user_files.scan_engine VARCHAR(12)`: `STRUCTURAL` (type and structure checks only, not an antivirus scan) or `CLAMAV`
  (ClamAV also scanned it and found nothing). Existing rows are `STRUCTURAL`.
- `maintenance_runs`: `job`, `triggered_by` (admin, NULL at startup), `started_at`/`finished_at`, `candidates`, `moved`,
  `skipped`, `failed`, `detail`. Written by the legacy DM image move (`legacy-dm-media-private`).
- Legacy DM images are marked private the same way new ones are: their `media_assets.storage_key` moves from `media/` to
  `private/<same name>` (variants follow the key).
- Image originals in storage carry no EXIF/XMP/IPTC/text metadata (stripped before storing). Private files are byte-exact.

## Account security (V18, D-041, D-042)
- `account_security` (1:1 with `users`, created lazily): `webauthn_user_handle` (32 random bytes), `totp_secret_enc` /
  `totp_pending_enc` (AES-GCM ciphertext `v1:…`), `totp_enabled_at`, `totp_last_step` (replay guard),
  `mfa_failed_attempts`, `mfa_locked_until`, `new_signin_alerts` (default true).
- `totp_recovery_codes`: SHA-256 of (user id, code), `used_at`.
- `mfa_login_challenges`: hash of the second-step token, `attempts`, `expires_at`, `used_at`.
- `user_passkeys`: `credential_id` (unique), `public_key_cose`, `sign_count`, `name`, `transports`, `discoverable`,
  `backup_eligible`, `backed_up`, `flagged_at`, `last_used_at`.
- `webauthn_challenges`: `request_id`, `kind` (`REGISTER`/`AUTHENTICATE`), `user_id`, `binding_hash` (hash of the
  `howlers_wac` cookie), the library's request JSON, `expires_at`, `used_at`. Purged an hour after expiry.
- `known_devices`: `device_hash` (keyed hash of device cookie + browser family + OS family), `browser_family`,
  `os_family`, `network_hash` (12 hex chars of a keyed hash of the /24 or /48 prefix), first/last seen. Unique per user.
- `refresh_tokens.auth_method` (nullable): how the session signed in, carried through rotation.
## Pack rooms, reactions and automod (V17, D-043 to D-045)
- `dm_conversations` gains `kind` (`DIRECT` default | `ROOM`), `name`, `icon_emoji`, `created_by`; `pair_key` is now
  nullable, with a check that DIRECT rows have a pair key and ROOM rows have none and a name.
- `dm_participants.role` (`OWNER` | `ADMIN` | `MEMBER`, default MEMBER) with a partial unique index allowing one OWNER
  per conversation. `dm_messages` gains `kind` (`USER` default | `SYSTEM`), `system_event`, `system_target_id`.
- `reactions (target_type POST|COMMENT|MESSAGE, target_id, user_id, reaction, created_at)`, primary key on all four
  identity columns, indexed by target and by user. No foreign key to the target: reads re-check visibility.
- `automod_rules` (per community: `kind`, `pattern`, `threshold`, `applies_to`, `action`, author-facing `reason`,
  `enabled`, `created_by`) and `automod_hits` (rule id and name, the rule's action and the final outcome, target,
  author, detail, excerpt). Automod also writes to `moderation_actions` (`AUTOMOD_HOLD`, `AUTOMOD_FLAG`, `AUTOMOD_RULE`,
  and ordinary `REMOVE_*` rows with no moderator) and, for holds, to `spam_flags` with rule `AUTOMOD`.
