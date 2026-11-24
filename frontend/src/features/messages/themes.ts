import type { CSSProperties } from 'react';
import type { ChatThemeId } from '@/types/messages';

/*
 * Chat themes: shared per conversation (PUT /me/conversations/{id}/theme). Each theme has a light and a dark
 * palette so it reads well in either app theme. Wallpapers are CSS only: a gradient plus a repeating pattern drawn
 * with gradients in the pattern colour. chatTheme.css picks the palette for the current app theme.
 */

export interface ThemePalette {
  /** Wallpaper gradient (any CSS background-image). */
  bg: string;
  /** Colour of the wallpaper pattern (with alpha). */
  pattern: string;
  /** Outgoing bubble gradient stops; the text colour must pass 4.5:1 against both. */
  out: [string, string];
  outFg: string;
  incoming: string;
  inFg: string;
  /** Composer and button accent. */
  accent: string;
  accentFg: string;
}

export interface ChatTheme {
  id: ChatThemeId;
  name: string;
  vibe: string;
  /** Repeating wallpaper pattern using `var(--ct-pattern)`, and its tile size. */
  patternImage: string;
  patternSize: string;
  light: ThemePalette;
  dark: ThemePalette;
}

const P = 'var(--ct-pattern)';
const patterns = {
  dots: { image: `radial-gradient(${P} 1.2px, transparent 1.6px)`, size: '18px 18px' },
  stars: {
    image: `radial-gradient(${P} 1px, transparent 1.5px), radial-gradient(${P} 1.6px, transparent 2px)`,
    size: '46px 46px, 97px 97px',
  },
  waves: {
    image: `radial-gradient(circle at 50% 100%, transparent 14px, ${P} 15px, ${P} 16px, transparent 17px)`,
    size: '36px 18px',
  },
  leaves: {
    image: `radial-gradient(ellipse 6px 11px at 30% 30%, ${P} 90%, transparent 100%), radial-gradient(ellipse 5px 9px at 75% 75%, ${P} 90%, transparent 100%)`,
    size: '54px 54px',
  },
  grid: {
    image: `linear-gradient(${P} 1px, transparent 1px), linear-gradient(90deg, ${P} 1px, transparent 1px)`,
    size: '28px 28px',
  },
  diagonal: { image: `repeating-linear-gradient(45deg, ${P} 0 1px, transparent 1px 14px)`, size: 'auto' },
  hearts: {
    image: `radial-gradient(circle at 40% 40%, ${P} 3px, transparent 3.5px), radial-gradient(circle at 60% 40%, ${P} 3px, transparent 3.5px)`,
    size: '40px 40px',
  },
  ribbons: {
    image: `repeating-linear-gradient(-20deg, transparent 0 22px, ${P} 22px 24px, transparent 24px 60px)`,
    size: 'auto',
  },
  sparks: {
    image: `radial-gradient(${P} 1.5px, transparent 2px), radial-gradient(${P} 1px, transparent 1.5px)`,
    size: '30px 30px, 13px 13px',
  },
  petals: {
    image: `radial-gradient(ellipse 4px 7px at 25% 30%, ${P} 90%, transparent 100%), radial-gradient(ellipse 7px 4px at 70% 70%, ${P} 90%, transparent 100%)`,
    size: '48px 48px',
  },
} as const;

const theme = (
  id: ChatThemeId,
  name: string,
  vibe: string,
  pattern: keyof typeof patterns,
  light: ThemePalette,
  dark: ThemePalette,
): ChatTheme => ({ id, name, vibe, patternImage: patterns[pattern].image, patternSize: patterns[pattern].size, light, dark });

export const CHAT_THEMES: readonly ChatTheme[] = [
  theme('classic', 'Classic', 'Clean Howlers indigo', 'dots',
    { bg: 'linear-gradient(180deg, #f4f5fb, #e8ebf5)', pattern: 'rgb(71 74 232 / 0.09)', out: ['#474ae8', '#4b3bd0'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#0f1a1c', accent: '#474ae8', accentFg: '#ffffff' },
    { bg: 'linear-gradient(180deg, #0b0e10, #12161c)', pattern: 'rgb(122 125 255 / 0.08)', out: ['#4f52e0', '#4636c4'], outFg: '#ffffff', incoming: '#1f252b', inFg: '#eef1f3', accent: '#7a7dff', accentFg: '#0b0e10' }),
  theme('midnight', 'Midnight', 'Starry late-night talks', 'stars',
    { bg: 'linear-gradient(160deg, #dfe6f7, #c9d3ee)', pattern: 'rgb(30 42 90 / 0.18)', out: ['#1e2a5a', '#2c3e8f'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#121a33', accent: '#2c3e8f', accentFg: '#ffffff' },
    { bg: 'linear-gradient(160deg, #050816, #0d1433 60%, #1a1040)', pattern: 'rgb(255 255 255 / 0.22)', out: ['#3546b8', '#4f37b0'], outFg: '#ffffff', incoming: '#161d3a', inFg: '#e6e9ff', accent: '#8ea2ff', accentFg: '#050816' }),
  theme('ocean', 'Ocean', 'Calm tides and deep blue', 'waves',
    { bg: 'linear-gradient(180deg, #e0f4fb, #bfe6f3)', pattern: 'rgb(3 105 161 / 0.12)', out: ['#0369a1', '#0e7490'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#0c2a36', accent: '#0369a1', accentFg: '#ffffff' },
    { bg: 'linear-gradient(180deg, #03131c, #062a3a)', pattern: 'rgb(56 189 248 / 0.1)', out: ['#0e7490', '#0369a1'], outFg: '#ffffff', incoming: '#0d2b38', inFg: '#e0f4fb', accent: '#38bdf8', accentFg: '#03131c' }),
  theme('sunset', 'Sunset', 'Golden hour, warm and hazy', 'ribbons',
    { bg: 'linear-gradient(160deg, #ffe8d6, #ffd1dc 55%, #e8d5ff)', pattern: 'rgb(194 65 12 / 0.08)', out: ['#c2410c', '#be185d'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#3b1a12', accent: '#b03a0a', accentFg: '#ffffff' },
    { bg: 'linear-gradient(160deg, #2a0f12, #3a1030 60%, #1e1240)', pattern: 'rgb(251 146 60 / 0.08)', out: ['#c2410c', '#be185d'], outFg: '#ffffff', incoming: '#3a1a24', inFg: '#ffe8d6', accent: '#fb923c', accentFg: '#2a0f12' }),
  theme('forest', 'Forest', 'Mossy, quiet, outdoors', 'leaves',
    { bg: 'linear-gradient(180deg, #e7f3e4, #cfe5c7)', pattern: 'rgb(22 101 52 / 0.1)', out: ['#166534', '#15803d'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#14281a', accent: '#166534', accentFg: '#ffffff' },
    { bg: 'linear-gradient(180deg, #07140b, #0f2416)', pattern: 'rgb(74 222 128 / 0.07)', out: ['#15803d', '#166534'], outFg: '#ffffff', incoming: '#15291b', inFg: '#e2f2e0', accent: '#4ade80', accentFg: '#07140b' }),
  theme('candy', 'Candy', 'Sweet pink and bubblegum', 'hearts',
    { bg: 'linear-gradient(135deg, #ffe4f1, #e0f0ff)', pattern: 'rgb(219 39 119 / 0.1)', out: ['#be185d', '#9333ea'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#3a0f2a', accent: '#be185d', accentFg: '#ffffff' },
    { bg: 'linear-gradient(135deg, #2a0b1f, #14183a)', pattern: 'rgb(244 114 182 / 0.09)', out: ['#be185d', '#7e22ce'], outFg: '#ffffff', incoming: '#2e1430', inFg: '#ffe4f1', accent: '#f472b6', accentFg: '#2a0b1f' }),
  theme('neon', 'Neon', 'Electric city nights', 'grid',
    { bg: 'linear-gradient(135deg, #ede9fe, #cffafe)', pattern: 'rgb(109 40 217 / 0.08)', out: ['#6d28d9', '#0e7490'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#1e1033', accent: '#6d28d9', accentFg: '#ffffff' },
    { bg: 'linear-gradient(135deg, #07010f, #0f0524)', pattern: 'rgb(34 211 238 / 0.09)', out: ['#a21caf', '#6d28d9'], outFg: '#ffffff', incoming: '#160a2e', inFg: '#e9d5ff', accent: '#22d3ee', accentFg: '#07010f' }),
  theme('lavender', 'Lavender', 'Soft, dreamy and light', 'dots',
    { bg: 'linear-gradient(180deg, #f3ecff, #e6dcff)', pattern: 'rgb(124 58 237 / 0.1)', out: ['#7c3aed', '#6d28d9'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#2a1a4a', accent: '#6d28d9', accentFg: '#ffffff' },
    { bg: 'linear-gradient(180deg, #120c22, #1d1336)', pattern: 'rgb(196 181 253 / 0.08)', out: ['#6d28d9', '#5b21b6'], outFg: '#ffffff', incoming: '#241a3d', inFg: '#ede4ff', accent: '#c4b5fd', accentFg: '#120c22' }),
  theme('mono', 'Mono', 'Minimal black and white', 'grid',
    { bg: 'linear-gradient(180deg, #f4f4f5, #e4e4e7)', pattern: 'rgb(24 24 27 / 0.05)', out: ['#18181b', '#27272a'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#18181b', accent: '#18181b', accentFg: '#ffffff' },
    { bg: 'linear-gradient(180deg, #09090b, #18181b)', pattern: 'rgb(244 244 245 / 0.05)', out: ['#f4f4f5', '#e4e4e7'], outFg: '#09090b', incoming: '#27272a', inFg: '#f4f4f5', accent: '#f4f4f5', accentFg: '#09090b' }),
  theme('aurora', 'Aurora', 'Northern lights shimmer', 'sparks',
    { bg: 'linear-gradient(160deg, #d9fbe8, #d6ecff 50%, #ecdcff)', pattern: 'rgb(4 120 87 / 0.12)', out: ['#047857', '#1d4ed8'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#0b2530', accent: '#047857', accentFg: '#ffffff' },
    { bg: 'linear-gradient(160deg, #03161a, #071a33 50%, #1a0b33)', pattern: 'rgb(94 234 212 / 0.12)', out: ['#0f766e', '#4338ca'], outFg: '#ffffff', incoming: '#102a33', inFg: '#dcfce7', accent: '#5eead4', accentFg: '#03161a' }),
  theme('ember', 'Ember', 'Fireside glow', 'sparks',
    { bg: 'linear-gradient(180deg, #fff1e6, #ffd9c2)', pattern: 'rgb(185 28 28 / 0.1)', out: ['#b91c1c', '#c2410c'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#3a120a', accent: '#b91c1c', accentFg: '#ffffff' },
    { bg: 'linear-gradient(180deg, #1a0805, #2e0f08)', pattern: 'rgb(251 146 60 / 0.12)', out: ['#b91c1c', '#9a3412'], outFg: '#ffffff', incoming: '#33150e', inFg: '#ffe4d6', accent: '#fb923c', accentFg: '#1a0805' }),
  theme('sakura', 'Sakura', 'Cherry blossom spring', 'petals',
    { bg: 'linear-gradient(180deg, #fff0f5, #ffe1ec)', pattern: 'rgb(190 24 93 / 0.12)', out: ['#be185d', '#9d174d'], outFg: '#ffffff', incoming: '#ffffff', inFg: '#3d0f22', accent: '#be185d', accentFg: '#ffffff' },
    { bg: 'linear-gradient(180deg, #1f0a14, #2d0f1f)', pattern: 'rgb(249 168 212 / 0.1)', out: ['#9d174d', '#be185d'], outFg: '#ffffff', incoming: '#351525', inFg: '#ffe1ec', accent: '#f9a8d4', accentFg: '#1f0a14' }),
];

export const DEFAULT_THEME_ID: ChatThemeId = 'classic';
export const THEME_IDS: readonly ChatThemeId[] = CHAT_THEMES.map((t) => t.id);

const byId = new Map(CHAT_THEMES.map((t) => [t.id, t]));

export const isThemeId = (v: unknown): v is ChatThemeId => typeof v === 'string' && byId.has(v as ChatThemeId);

/** The theme for a conversation's stored id; unknown, null or "" fall back to Classic. */
export function getTheme(id: string | null | undefined): ChatTheme {
  return (isThemeId(id) && byId.get(id)) || byId.get(DEFAULT_THEME_ID)!;
}

/** CSS custom properties for both palettes; chatTheme.css chooses `-l` or `-d` from the app theme. */
export function themeVars(t: ChatTheme): CSSProperties {
  const vars: Record<string, string> = {
    '--ct-pattern-image': t.patternImage,
    '--ct-pattern-size': t.patternSize,
  };
  for (const [suffix, p] of [['l', t.light], ['d', t.dark]] as const) {
    vars[`--ct-bg-${suffix}`] = p.bg;
    vars[`--ct-pattern-${suffix}`] = p.pattern;
    vars[`--ct-out-1-${suffix}`] = p.out[0];
    vars[`--ct-out-2-${suffix}`] = p.out[1];
    vars[`--ct-out-fg-${suffix}`] = p.outFg;
    vars[`--ct-in-${suffix}`] = p.incoming;
    vars[`--ct-in-fg-${suffix}`] = p.inFg;
    vars[`--ct-accent-${suffix}`] = p.accent;
    vars[`--ct-accent-fg-${suffix}`] = p.accentFg;
  }
  return vars as CSSProperties;
}

/* ------------------------------------------------------------------ contrast (WCAG 2.x) */

function channel(c: number): number {
  const s = c / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}

/** Relative luminance of a `#rrggbb` colour. */
export function luminance(hex: string): number {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex.trim());
  if (!m) throw new Error(`Not a #rrggbb colour: ${hex}`);
  const [r, g, b] = [m[1], m[2], m[3]].map((h) => channel(parseInt(h, 16)));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrastRatio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}
