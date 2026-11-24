import type { CSSProperties } from 'react';
import type { ChatThemeId } from '@/types/messages';

/*
 * Chat themes: shared per conversation (PUT /me/conversations/{id}/theme), in the spirit of Instagram's chat themes.
 * Your bubbles share one gradient that spans the whole chat (fixed to the screen), so colours shift as messages scroll,
 * and the wallpaper is a soft mesh of coloured glows with a fine grain. Each theme has a light and a dark palette;
 * chatTheme.css picks one for the app theme. Everything is CSS: no images.
 */

export interface ThemePalette {
  /** Wallpaper: soft radial glows over a base gradient (any CSS background-image list). */
  bg: string;
  /** Outgoing gradient stops from the top of the screen to the bottom; the text must pass 4.5:1 on each. */
  out: [string, string, string];
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
/** Optional fine details over the mesh, drawn in a faint tint of the accent. Most themes use none. */
const patterns = {
  none: { image: 'none', size: 'auto' },
  stars: {
    image: `radial-gradient(${P} 0.8px, transparent 1.2px), radial-gradient(${P} 1.2px, transparent 1.6px)`,
    size: '61px 61px, 137px 137px',
  },
  sparks: {
    image: `radial-gradient(${P} 1px, transparent 1.4px), radial-gradient(${P} 0.7px, transparent 1.1px)`,
    size: '73px 73px, 29px 29px',
  },
  petals: {
    image: `radial-gradient(ellipse 3px 6px at 25% 30%, ${P} 90%, transparent 100%), radial-gradient(ellipse 6px 3px at 70% 72%, ${P} 90%, transparent 100%)`,
    size: '96px 96px',
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
  theme('classic', "Howl", "Signature violet into indigo", 'none',
    { bg: "radial-gradient(70% 70% at 12% 8%, rgb(140 82 255 / 0.20), transparent 70%), radial-gradient(65% 65% at 90% 35%, rgb(64 120 255 / 0.16), transparent 70%), radial-gradient(70% 70% at 40% 100%, rgb(255 92 170 / 0.12), transparent 70%), linear-gradient(180deg, #f7f5ff, #eef1ff)", out: ["#8a2be2", "#5b3fe0", "#2f5be0"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#191433", accent: "#5b3fe0", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 15% 5%, rgb(140 82 255 / 0.30), transparent 70%), radial-gradient(65% 65% at 90% 40%, rgb(64 120 255 / 0.22), transparent 70%), radial-gradient(70% 70% at 35% 100%, rgb(255 92 170 / 0.16), transparent 70%), linear-gradient(180deg, #0b0918, #0d1022)", out: ["#8a2be2", "#5b3fe0", "#2f5be0"], outFg: "#ffffff", incoming: "#1e1b33", inFg: "#f1eeff", accent: "#b9a6ff", accentFg: "#0b0918" }),
  theme('midnight', "Velvet Night", "Deep navy, a few quiet stars", 'stars',
    { bg: "radial-gradient(70% 70% at 20% 10%, rgb(40 60 140 / 0.16), transparent 70%), radial-gradient(70% 70% at 85% 85%, rgb(90 60 160 / 0.12), transparent 70%), linear-gradient(170deg, #eef1fa, #dfe4f4)", out: ["#1c2a6b", "#2b2f8a", "#3b2a8f"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#141a33", accent: "#2b2f8a", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 20% 0%, rgb(70 90 200 / 0.28), transparent 70%), radial-gradient(70% 70% at 90% 90%, rgb(120 70 200 / 0.22), transparent 70%), linear-gradient(170deg, #04060f, #0a0f26 60%, #140b2a)", out: ["#2b3a9a", "#33309c", "#46309e"], outFg: "#ffffff", incoming: "#151a33", inFg: "#e6e9ff", accent: "#9fb0ff", accentFg: "#04060f" }),
  theme('ocean', "Tidal", "Lagoon teal fading into the deep", 'none',
    { bg: "radial-gradient(70% 70% at 10% 10%, rgb(0 170 200 / 0.18), transparent 70%), radial-gradient(70% 70% at 90% 60%, rgb(0 90 200 / 0.14), transparent 70%), radial-gradient(70% 70% at 40% 100%, rgb(0 200 170 / 0.12), transparent 70%), linear-gradient(180deg, #eefbfd, #dff1f8)", out: ["#00737e", "#0567a8", "#1d4fb8"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#0b2731", accent: "#0567a8", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 10% 0%, rgb(0 170 200 / 0.26), transparent 70%), radial-gradient(70% 70% at 90% 60%, rgb(0 90 200 / 0.22), transparent 70%), radial-gradient(70% 70% at 40% 100%, rgb(0 200 170 / 0.16), transparent 70%), linear-gradient(180deg, #02121a, #041d2e)", out: ["#00737e", "#0567a8", "#1d4fb8"], outFg: "#ffffff", incoming: "#0f2633", inFg: "#e2f6fb", accent: "#5fd4f0", accentFg: "#02121a" }),
  theme('sunset', "Golden Hour", "Amber, coral and dusk pink", 'none',
    { bg: "radial-gradient(70% 70% at 15% 5%, rgb(255 170 60 / 0.24), transparent 70%), radial-gradient(70% 70% at 90% 45%, rgb(255 90 110 / 0.16), transparent 70%), radial-gradient(70% 70% at 30% 100%, rgb(190 90 255 / 0.14), transparent 70%), linear-gradient(170deg, #fff6ee, #ffece9 55%, #f6ebff)", out: ["#b4520a", "#c22f4f", "#a3267a"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#3a1a12", accent: "#b23a1e", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 15% 0%, rgb(255 150 50 / 0.26), transparent 70%), radial-gradient(70% 70% at 90% 45%, rgb(255 80 110 / 0.20), transparent 70%), radial-gradient(70% 70% at 30% 100%, rgb(170 80 255 / 0.18), transparent 70%), linear-gradient(170deg, #1d0c08, #2a0c1c 55%, #1a0c2c)", out: ["#b4520a", "#c22f4f", "#a3267a"], outFg: "#ffffff", incoming: "#33191f", inFg: "#ffeee4", accent: "#ffad6b", accentFg: "#1d0c08" }),
  theme('forest', "Evergreen", "Moss, pine and morning light", 'none',
    { bg: "radial-gradient(70% 70% at 15% 10%, rgb(60 160 90 / 0.18), transparent 70%), radial-gradient(70% 70% at 85% 80%, rgb(160 190 60 / 0.14), transparent 70%), linear-gradient(180deg, #f3faf1, #e3f1e2)", out: ["#1f6f43", "#17694f", "#0f5f5a"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#14281a", accent: "#1f6f43", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 15% 0%, rgb(60 160 90 / 0.24), transparent 70%), radial-gradient(70% 70% at 85% 85%, rgb(160 190 60 / 0.14), transparent 70%), linear-gradient(180deg, #06120a, #0b1d12)", out: ["#1f6f43", "#17694f", "#0f5f5a"], outFg: "#ffffff", incoming: "#142a1c", inFg: "#e3f3e2", accent: "#7fdc9a", accentFg: "#06120a" }),
  theme('candy', "Bubblegum", "Pink to lilac, sweet but sharp", 'none',
    { bg: "radial-gradient(70% 70% at 10% 15%, rgb(255 105 180 / 0.20), transparent 70%), radial-gradient(70% 70% at 90% 70%, rgb(150 110 255 / 0.18), transparent 70%), linear-gradient(135deg, #fff2f8, #f3efff)", out: ["#c2185b", "#a11fa0", "#7a2cc4"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#3a0f2a", accent: "#b0195f", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 10% 10%, rgb(255 105 180 / 0.26), transparent 70%), radial-gradient(70% 70% at 90% 75%, rgb(150 110 255 / 0.24), transparent 70%), linear-gradient(135deg, #1d0816, #140c2a)", out: ["#c2185b", "#a11fa0", "#7a2cc4"], outFg: "#ffffff", incoming: "#2c1530", inFg: "#ffe6f2", accent: "#ff8cc6", accentFg: "#1d0816" }),
  theme('neon', "Afterglow", "Magenta, violet and electric cyan", 'none',
    { bg: "radial-gradient(70% 70% at 10% 10%, rgb(220 60 255 / 0.18), transparent 70%), radial-gradient(70% 70% at 90% 85%, rgb(0 200 255 / 0.18), transparent 70%), radial-gradient(60% 60% at 60% 40%, rgb(120 80 255 / 0.12), transparent 70%), linear-gradient(135deg, #f8f2ff, #ecfbff)", out: ["#a21caf", "#6d28d9", "#0b63a8"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#1e1033", accent: "#6d28d9", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 10% 5%, rgb(220 60 255 / 0.30), transparent 70%), radial-gradient(70% 70% at 90% 85%, rgb(0 200 255 / 0.24), transparent 70%), radial-gradient(60% 60% at 60% 45%, rgb(120 80 255 / 0.16), transparent 70%), linear-gradient(135deg, #07010f, #0a0620)", out: ["#a21caf", "#6d28d9", "#0b63a8"], outFg: "#ffffff", incoming: "#170d2c", inFg: "#efe3ff", accent: "#5fe3ff", accentFg: "#07010f" }),
  theme('lavender', "Lavender Haze", "Soft lilac, calm and dreamy", 'none',
    { bg: "radial-gradient(70% 70% at 15% 10%, rgb(170 130 255 / 0.20), transparent 70%), radial-gradient(70% 70% at 85% 80%, rgb(255 170 230 / 0.14), transparent 70%), linear-gradient(180deg, #f8f4ff, #efe8ff)", out: ["#7a4fd6", "#6a45c9", "#5a3db8"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#2a1a4a", accent: "#6a45c9", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 15% 5%, rgb(170 130 255 / 0.24), transparent 70%), radial-gradient(70% 70% at 85% 85%, rgb(255 150 220 / 0.14), transparent 70%), linear-gradient(180deg, #100b1e, #181030)", out: ["#7a4fd6", "#6a45c9", "#5a3db8"], outFg: "#ffffff", incoming: "#231a3a", inFg: "#efe7ff", accent: "#cdb8ff", accentFg: "#100b1e" }),
  theme('mono', "Noir", "Black, white and nothing else", 'none',
    { bg: "radial-gradient(70% 70% at 20% 10%, rgb(0 0 0 / 0.05), transparent 70%), radial-gradient(70% 70% at 80% 90%, rgb(0 0 0 / 0.06), transparent 70%), linear-gradient(180deg, #fafafa, #ededee)", out: ["#111113", "#1c1c20", "#2a2a2f"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#18181b", accent: "#18181b", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 20% 5%, rgb(255 255 255 / 0.05), transparent 70%), radial-gradient(70% 70% at 80% 90%, rgb(255 255 255 / 0.04), transparent 70%), linear-gradient(180deg, #060607, #111114)", out: ["#f5f5f6", "#e8e8ea", "#dcdcdf"], outFg: "#0a0a0b", incoming: "#1f1f23", inFg: "#f4f4f5", accent: "#f4f4f5", accentFg: "#09090b" }),
  theme('aurora', "Northern Lights", "Green and violet ribbons of light", 'sparks',
    { bg: "radial-gradient(70% 70% at 10% 5%, rgb(40 220 150 / 0.20), transparent 70%), radial-gradient(70% 70% at 70% 40%, rgb(80 140 255 / 0.16), transparent 70%), radial-gradient(70% 70% at 30% 100%, rgb(170 90 255 / 0.16), transparent 70%), linear-gradient(170deg, #effcf6, #eaf2ff 55%, #f3ecff)", out: ["#04785a", "#0e6a8a", "#4a3bc0"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#0b2530", accent: "#04785a", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 10% 0%, rgb(40 220 150 / 0.26), transparent 70%), radial-gradient(70% 70% at 70% 40%, rgb(80 140 255 / 0.20), transparent 70%), radial-gradient(70% 70% at 30% 100%, rgb(170 90 255 / 0.22), transparent 70%), linear-gradient(170deg, #021310, #041427 55%, #120a26)", out: ["#04785a", "#0e6a8a", "#4a3bc0"], outFg: "#ffffff", incoming: "#0f2630", inFg: "#dcfce9", accent: "#6ff0c4", accentFg: "#021310" }),
  theme('ember', "Ember", "Firelight red and smoky amber", 'sparks',
    { bg: "radial-gradient(70% 70% at 15% 10%, rgb(255 120 40 / 0.20), transparent 70%), radial-gradient(70% 70% at 85% 85%, rgb(220 40 40 / 0.14), transparent 70%), linear-gradient(180deg, #fff6f0, #ffeadd)", out: ["#b8361a", "#a82c1f", "#8f2414"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#3a120a", accent: "#a82c1f", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 15% 0%, rgb(255 120 40 / 0.26), transparent 70%), radial-gradient(70% 70% at 85% 90%, rgb(220 40 40 / 0.20), transparent 70%), linear-gradient(180deg, #160604, #240b06)", out: ["#b8361a", "#a82c1f", "#8f2414"], outFg: "#ffffff", incoming: "#301610", inFg: "#ffe9dd", accent: "#ffa06a", accentFg: "#160604" }),
  theme('sakura', "Sakura", "Cherry blossom, blush and plum", 'petals',
    { bg: "radial-gradient(70% 70% at 15% 10%, rgb(255 130 170 / 0.20), transparent 70%), radial-gradient(70% 70% at 85% 80%, rgb(200 120 220 / 0.12), transparent 70%), linear-gradient(180deg, #fff5f8, #ffe9f0)", out: ["#b81d5c", "#a0195f", "#86185f"], outFg: "#ffffff", incoming: "#ffffff", inFg: "#3d0f22", accent: "#a0195f", accentFg: "#ffffff" },
    { bg: "radial-gradient(70% 70% at 15% 0%, rgb(255 130 170 / 0.24), transparent 70%), radial-gradient(70% 70% at 85% 85%, rgb(200 120 220 / 0.16), transparent 70%), linear-gradient(180deg, #1a0911, #280d1c)", out: ["#b81d5c", "#a0195f", "#86185f"], outFg: "#ffffff", incoming: "#33162a", inFg: "#ffe4ee", accent: "#ffa8cb", accentFg: "#1a0911" }),
];

export const DEFAULT_THEME_ID: ChatThemeId = 'classic';
export const THEME_IDS: readonly ChatThemeId[] = CHAT_THEMES.map((t) => t.id);

const byId = new Map(CHAT_THEMES.map((t) => [t.id, t]));

export const isThemeId = (v: unknown): v is ChatThemeId => typeof v === 'string' && byId.has(v as ChatThemeId);

/** The theme for a conversation's stored id; unknown, null or "" fall back to the default (Howl). */
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
    vars[`--ct-out-1-${suffix}`] = p.out[0];
    vars[`--ct-out-2-${suffix}`] = p.out[1];
    vars[`--ct-out-3-${suffix}`] = p.out[2];
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
