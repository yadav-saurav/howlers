# Architecture

## Shape: modular monolith

```
 Browser ──HTTPS──▶ nginx (web)  ──/api──▶  Spring Boot (backend) ──▶ PostgreSQL 17
   React SPA          static + proxy          modules below          ├─▶ Redis 7 (rate limits; cache later)
                                                                     └─▶ Object storage (local FS now, S3 later)
```

One deployable backend, one SPA, one database. Module boundaries are package boundaries (`app.howlers.<module>`).
Modules talk through services, never through each other's tables, except read models that intentionally join
(e.g. `PostQueryService` joins communities for visibility). Nothing here needs a second service yet; see D-001.

### Backend modules (`backend/src/main/java/app/howlers`)
| Module | Responsibility |
|---|---|
| `common` | config properties, error model, trace ids, JWT/security, rate limiter, HTML sanitising, cursors |
| `auth` | register/login, refresh-token rotation with reuse detection, sessions, email verification, password reset |
| `user` | profiles, settings, avatar/banner, follow graph, deactivation/anonymising deletion |
| `topic`, `onboarding` | topic taxonomy; first-run interests → communities → people → theme |
| `community` | communities, roles, rules, flairs, membership; **`CommunityAccess` is the only permission authority** |
| `post` | write model (JPA) + read model (`PostQueryService`: id-page query → batched hydration) |
| `vote` | concurrency-safe post/comment votes, karma, ranking maths (`ScoreMath`) |
| `comment` | adjacency-list threads, windowed fan-out preloading, lazy replies |
| `feed` | tab = scope × sort composition |
| `search` | PostgreSQL FTS (weighted tsvector + GIN), suggestions |
| `notification` | grouped, de-duplicated in-app notifications |
| `storage` | storage abstraction, magic-byte image validation, media assets |
| `seed` | deterministic demo content from `seed/demo.json` |

Each module holds its controllers, services, repositories/DAOs, DTOs (`dto/`) and entities. Entities never
leave a service; controllers only map HTTP ⇄ DTO.

### Data access style
- **JPA/Hibernate** for aggregates with real lifecycle (User, Community + rules/flairs, Post, tokens).
- **`JdbcClient` SQL** for join tables, counters and read models where precise SQL matters (keyset pagination,
  window functions, `ON CONFLICT`, advisory locks). Schema is owned by **Flyway**; Hibernate only validates.

### Request lifecycle
`TraceIdFilter` (X-Trace-Id, MDC) → `JwtAuthFilter` (Bearer → `AuthUser`; invalid token ⇒ 401 so the SPA
refreshes) → Spring Security route rules (coarse) → controller → service (fine-grained checks via
`CommunityAccess` / ownership) → `GlobalExceptionHandler` (single error shape, no internals).

### Auth model
- Access token: HS256 JWT, 15 min, held in SPA memory only.
- Refresh token: 48 random bytes, `HttpOnly; SameSite=Strict; Path=/api/v1/auth` cookie, stored as SHA-256.
  Every refresh rotates; a reused token revokes its whole family (device session). Origin allow-list check on
  the cookie endpoints. Password reset revokes all sessions.

### Frontend (`frontend/src`)
`app` (providers, router, guards) · `routes` (pages) · `layouts` · `features/<x>/{api,hooks,components}` ·
`components/ui` (design-system primitives) · `services/apiClient` (single-flight refresh) · `store` (Zustand
auth) · `design-system` (brand + tokens). Server state lives only in TanStack Query.

### Observability
JSON-able log pattern with `traceId`; `/actuator/health` with liveness/readiness, DB, Redis and storage
indicators. Secrets, tokens and passwords are never logged (refresh/email tokens exist only as hashes).

### Scaling path (when, not now)
Read replicas for feeds → Redis caching of hot feed pages → move search to a dedicated engine behind
`SearchService` → extract notifications/messaging to a WebSocket gateway. Each is a local change because of
the boundaries above.
