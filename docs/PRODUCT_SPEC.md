# Howlers — product spec

**Working brand:** Howlers — *communities that build knowledge, in layers.* (Centralised in
`frontend/src/design-system/brand.ts`; replaceable.)

## Thesis
Discussion platforms are great at producing conversation and poor at preserving what it teaches. Howlers keeps
the familiar model (communities, posts, threaded replies, votes) and adds layers that turn threads into
knowledge: structured argument maps, digests, a library, grounded AI answers, long-form audio, and recognition
and support for the moderators who keep communities healthy.

## Principles
1. Communities generate knowledge, not just content.
2. Discussions should be easier to understand, not harder to navigate.
3. People who maintain communities are recognised and supported.
4. Popularity is a signal, not truth: rank by quality and keep minority views visible.
5. No fake data, no dead buttons, and no claims (E2EE, consensus, expertise) the system can't back.

## Personas
- **Learner**: reads more than posts. Needs digests, a library and trustworthy answers.
- **Contributor**: answers questions. Needs recognition by topic, not one global number.
- **Moderator**: keeps a community healthy. Needs tools, transparency and (optionally) financial support.
- **Founder of a community**: needs fast setup, rules, flairs and growth signals.

## Feature map and phase
| Area | Phase | Status |
|---|---|---|
| Auth, sessions, verification, reset, onboarding, profiles, settings | 1 | ✅ |
| Communities, roles, rules, flairs, membership | 2 | ✅ |
| Posts (text/link/image/poll), voting, karma, nested comments | 2 | ✅ |
| Feeds (For You/Following/Latest/Popular/Rising/Top), search, saved, notifications | 2 | ✅ |
| Reporting, blocking, mod queue, bans, locks/pins, appeals, audit log | 3 | ✅ |
| Topic reputation | 3–4 | ✅ v1 |
| Admin dashboard, mute, spam heuristics | 3 | ✅ |
| Smart Collections, Context Mode, Thread Digest | 4 | ✅ |
| Community Pulse | 4 | ✅ |
| Direct messages (1:1) and real-time events (messages, badges, live feeds and threads) | 2–4 | ✅ |
| Community AI (RAG with citations) | 5 | ✅ |
| Podcasts (upload-first; AI script drafting; audio generation only with a configured TTS provider) | 6 | ✅ |
| Moderator funding (demo mode until a provider is configured) | 8 | ✅ demo mode; Stripe hosted Checkout in test mode (D-034) |
| Secure file sharing → E2EE | 7 | ✅ (private files, share links, browser-side E2EE; see SECURITY_E2EE.md) |
| Moderator leaderboard | 9 | ✅ |

See ROADMAP.md for scope per phase and IMPLEMENTATION_STATUS.md for what exists today.
