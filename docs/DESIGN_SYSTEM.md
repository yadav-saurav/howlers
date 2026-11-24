# Howlers design system

Working brand: **Howlers** — "knowledge in layers". The name, tagline, logo mark and colors live in
`frontend/src/design-system/brand.ts` and `frontend/src/design-system/tokens.css`. Change them there and nowhere else.

## v3 update (accessibility audit, D-027)
`tokens.css` is the source of truth; the v1 table further down is historical. Changed for WCAG AA contrast:
`--text-faint` `138 148 153` (dark) / `99 108 113` (light); light `--accent` `0 110 76`, `--warning` `133 92 0`,
`--info` `24 94 200`, `--danger` `176 45 38`; dark `--brand-fg` `11 14 16` (dark text on the indigo button, 5.7:1).
Custom colours (community flairs, stance chips) tint a chip and its dot or icon; their label always uses `--text`.
Avatar initials are decorative generated content. See `docs/ACCESSIBILITY.md`.

## v2 update (layout pass)
- Dark theme is near-black `#0B0E10` with content directly on the background (no boxed feed); hover tints rows.
- **Brand indigo** (`--brand`, `#7A7DFF` dark / `#474AE8` light) is used for primary buttons and links. **Jade** (`--accent`) is kept
  for upvotes and positive state, and **violet** (`--down`) for downvotes.
- Buttons, votes and actions are **pills** (`rounded-full`). Cards and media use `rounded-2xl`. Panels in the right rail use
  `surface-2` at 60% with no border.
- Post titles use the **sans** at 18px semibold (scanability); the serif is reserved for the wordmark and landing headlines.
- Feeds offer **Card** and **Compact** views (remembered per device). Sorting is a dropdown (`Best ▾`), not a tab row.
- Comments: a 28px avatar column with a clickable thread line (collapse), body aligned to the username, pill actions.

## Principles
1. **Editorial, not dashboard.** Content is typography first. Chrome is quiet. Titles use a serif and
   body/UI uses a sans.
2. **Dense but breathable.** Feeds are list-first (compact rows with an optional media thumb), not giant cards.
3. **One accent.** Jade is reserved for primary actions, the active nav item and upvotes. Everything else is neutral.
4. **No decoration that doesn't carry meaning.** No gradients behind content, no glass, no emoji-as-icons.
5. **Motion explains state changes.** It never decorates. Everything honours `prefers-reduced-motion`.

## Color tokens (CSS variables, `rgb` triplets so Tailwind can apply alpha)
| Token | Dark (default) | Light | Use |
|---|---|---|---|
| `--bg` | `#0E0F11` | `#F7F6F3` | app background |
| `--surface` | `#15171A` | `#FFFFFF` | panels, post rows |
| `--surface-2` | `#1C1F23` | `#F1EFEA` | hover, inputs, nested comments |
| `--border` | `#272A30` | `#E3E0D8` | hairlines |
| `--text` | `#E8E6E1` | `#1A1A18` | primary text |
| `--text-muted` | `#9A9890` | `#5E5C55` | meta |
| `--text-faint` | `#6B6A64` | `#8C8A82` | timestamps, placeholders |
| `--accent` | `#3FB68B` | `#13805C` | primary, upvote |
| `--accent-fg` | `#06140E` | `#FFFFFF` | text on accent |
| `--down` | `#8B7CF6` | `#6A5AE0` | downvote |
| `--danger` | `#E5534B` | `#C4372F` | destructive |
| `--warning` | `#D4A72C` | `#9A6B00` | warnings |
| `--info` | `#58A6FF` | `#1F6FEB` | informational |

All text/background pairs meet WCAG AA (4.5:1 for body, 3:1 for large/UI).

## Typography
- UI/body: **Inter** (self-hosted via `@fontsource-variable/inter`). Base 15px / 1.55.
- Titles & editorial: **Source Serif 4** (`@fontsource-variable/source-serif-4`).
- Code: **JetBrains Mono**.
- Scale: 12 · 13 · 15 · 17 · 20 · 24 · 30 (`text-xs … text-3xl`). Page titles never exceed 30px inside the app;
  the landing hero may use 48–56px.

## Spacing, radius, elevation
- 4px base grid (Tailwind default spacing).
- Radius: `sm 4px` (badges, inputs), `md 6px` (buttons, rows), `lg 10px` (dialogs, panels). No `rounded-3xl`.
- Shadows only for floating layers (menus, dialogs, toasts): `0 8px 24px rgb(0 0 0 / .35)` dark, `.12` light.

## Components (`frontend/src/components/ui`)
Button (primary / secondary / ghost / danger; sm / md; loading), IconButton, Input, Textarea, Select, Checkbox,
Switch, Label + FieldError, Dialog (focus-trapped, Esc closes), DropdownMenu (roving focus), Tabs, Tooltip,
Toast (polite live region), Badge, Avatar (initials fallback), Skeleton, EmptyState, ErrorState, Spinner,
Pagination/“Load more” sentinel.

## Layout & breakpoints
`sm 640 · md 768 · lg 1024 · xl 1280 · 2xl 1440`.
- `≥ xl`: left nav (240) · feed (≤ 720) · right rail (320).
- `lg`: left nav collapses to icons (64) · feed · right rail.
- `md`: no rail (its modules move below the feed header); left nav becomes a drawer.
- `< md`: top bar + bottom tab bar (Home, Explore, Create, Inbox, Profile); comment indentation caps at 4 levels
  visually, deeper threads continue in “Continue thread →”.

## States
Every data view implements: skeleton loading, empty state (icon + one sentence + an action), error state
(message + retry), and success toasts for mutations.

## Landing page (Howlers)
The public landing page (`frontend/src/routes/landing/`) is the product's front door and has its own art direction.
It is the one place where the "no gradients behind content" and "page titles ≤ 30px" rules don't apply.

- **Always dark, by design.** It's a night-sky scene (indigo/violet sky, stars, aurora ribbons, a glowing moon, layered
  mountains and pines), so it ignores the app's light/dark theme. `PublicLayout` puts the `dark` class on the landing's
  wrapper, which scopes the dark tokens to that subtree only, and hides the theme toggle there (it would do nothing
  visible). Every other public page still follows the theme. Contrast was checked against the night background:
  body copy `#c9c4ee` and meta `#a9a3d6` on `#0d0b26`–`#2a1b63` are ≥ 7:1 and ≥ 5:1; axe reports no serious or critical
  issues in either app theme at 1280 px and 375 px.
- **Type.** Display headings use **Unbounded 800** (self-hosted via `@fontsource/unbounded`, latin subset only; the
  `font-display` Tailwind family), uppercase, tight tracking; the last line of each heading gets the "moon glow"
  gradient (`#ffe9b0 → #ffc4d8 → #b8a8ff`). Body stays Inter.
- **Mascots.** Original wolf pups drawn as inline SVG (`design-system/Pups.tsx`): one howling at the moon, one with
  headphones, one waving, one asleep in a nightcap. They're decorative and always `aria-hidden`. Inside the app they
  appear through `Mascot` (empty states, 404 and route errors, offline/maintenance errors, the full-page loader) with
  the theme-aware `theme` fur (D-048).
- **Product mocks** in the hero and the feature rows use the real dark-theme tokens (`#0B0E10`, `#1A1F23`, jade
  `#2ECC94`, indigo `#7A7DFF`) and real feature names. They are illustrations (the window says so); the marquee under the
  hero lists real communities from the API, falling back to feature names, never invented communities.
- **Motion** (`landing.css` + `motion.ts`): easing `--e: cubic-bezier(.22,1,.36,1)`, spring
  `cubic-bezier(.34,1.56,.64,1)`; an intro (masked line reveal, staggered fade-ups, art entrance) that starts when the
  display font is ready; ambient loops (twinkle, bob, blink, wave, hop, howl rings, typing dots, message cycles,
  floating +1s, marquee); pointer parallax and tilt cards for fine pointers only; scroll reveal via
  IntersectionObserver. Transforms and opacity only; loops pause off-screen and in hidden tabs; everything is off under
  `prefers-reduced-motion`, with all content visible and static.
- **Brand.** The public header and footer use `HowlersMark` (`design-system/HowlersMark.tsx`) and the tagline
  "Find your pack & howl together". `brand.ts` is renamed separately.

## Logo (Howlers)
- **Mark:** an original front-facing tribal wolf head built from sharp shards (`design-system/HowlersMark.tsx`), drawn as a
  left half plus its mirror image so it stays exactly symmetric. `HowlersMark` is the mark with an optional wordmark (header,
  sidebar, sign-in card, error page via `BrandMark`); `HowlersEmblem` adds "HOWLERS" lettered below (footer).
- **Colour:** a vertical gradient from the `--logo-1/2/3` tokens. Dark theme keeps the bright purple → blue → cyan
  (`#8e2fd0`, `#5a6be6`, `#36dcf4`); light theme uses deeper tones (`#6a1bb0`, `#3a48c9`, `#0b7896`, each at least 4.6:1
  on light surfaces) because the bright cyan drops to 1.7:1 on white.
- **Favicon:** `public/favicon.svg`, the same wolf on a dark rounded tile so it reads on light and dark browser tabs.
