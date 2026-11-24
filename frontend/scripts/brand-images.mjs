// Renders the PNG brand assets from the wolf mark in public/favicon.svg with headless Chromium (Playwright's):
//   public/og/howlers.png             1200×630 link-preview card (Open Graph / Twitter)
//   public/icons/icon-192.png, -512   app icons (rounded tile, "any" purpose)
//   public/icons/maskable-512.png     full-bleed tile with the wolf inside the 80% safe zone ("maskable")
//   public/icons/apple-touch-icon.png 180×180, opaque (iOS adds its own rounding)
//   public/icons/badge-72.png         monochrome white wolf on transparent (Android notification badge)
// Run once after changing the mark:  npm run brand:images   (the PNGs are committed; builds don't need Chromium).
import { chromium } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const favicon = readFileSync(resolve(root, 'public/favicon.svg'), 'utf8');
const wolfPaths = favicon.match(/<g fill="url\(#g\)">([\s\S]*?)<\/g><\/svg>/)?.[1];
if (!wolfPaths) throw new Error('Could not find the wolf paths in public/favicon.svg');

const font = (pkg, file) => pathToFileURL(resolve(root, 'node_modules', pkg, 'files', file)).href;
const unbounded = font('@fontsource/unbounded', 'unbounded-latin-700-normal.woff2');
const inter = font('@fontsource-variable/inter', 'inter-latin-wght-normal.woff2');

const gradient = (id) => `<linearGradient id="${id}" x1="0" y1="10" x2="0" y2="122" gradientUnits="userSpaceOnUse">
  <stop offset="0" stop-color="#8e2fd0"/><stop offset=".45" stop-color="#5a6be6"/><stop offset="1" stop-color="#36dcf4"/></linearGradient>`;

/** The wolf on its 100×124 grid, filled with the brand gradient (or a flat colour). */
const wolf = (fill = 'url(#g)') => `<g fill="${fill}">${wolfPaths}</g>`;

/** Deterministic star field so re-rendering gives the same image. */
function stars(n, w, h, seed = 7) {
  let s = seed;
  const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647);
  let out = '';
  for (let i = 0; i < n; i++) {
    const r = rnd() < 0.15 ? 1.8 : rnd() < 0.5 ? 1.2 : 0.8;
    out += `<circle cx="${(rnd() * w).toFixed(1)}" cy="${(rnd() * h).toFixed(1)}" r="${r}" fill="#f6f4ff" opacity="${(0.35 + rnd() * 0.6).toFixed(2)}"/>`;
  }
  return out;
}

const nightSky = (w, h) => `
  <radialGradient id="sky1" cx="78%" cy="18%" r="75%"><stop offset="0" stop-color="#8468ff" stop-opacity=".42"/><stop offset="1" stop-color="#8468ff" stop-opacity="0"/></radialGradient>
  <radialGradient id="sky2" cx="12%" cy="100%" r="70%"><stop offset="0" stop-color="#36dcf4" stop-opacity=".22"/><stop offset="1" stop-color="#36dcf4" stop-opacity="0"/></radialGradient>
  <linearGradient id="skyBase" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#07061a"/><stop offset=".6" stop-color="#16123d"/><stop offset="1" stop-color="#241b5c"/></linearGradient>
  <radialGradient id="moon" cx="38%" cy="34%" r="70%"><stop offset="0" stop-color="#fffaf0"/><stop offset=".55" stop-color="#ffeec4"/><stop offset="1" stop-color="#f6cf8c"/></radialGradient>
  <radialGradient id="halo" cx="50%" cy="50%" r="50%"><stop offset=".55" stop-color="#ffe9b0" stop-opacity=".35"/><stop offset="1" stop-color="#ffe9b0" stop-opacity="0"/></radialGradient>
  <linearGradient id="glow" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#ffe9b0"/><stop offset=".48" stop-color="#ffc4d8"/><stop offset="1" stop-color="#b8a8ff"/></linearGradient>`;

const ogCard = `<!doctype html><html><head><style>
  @font-face { font-family: Unbounded; src: url(${unbounded}) format('woff2'); font-weight: 700; }
  @font-face { font-family: Inter; src: url(${inter}) format('woff2'); font-weight: 100 900; }
  html, body { margin: 0; width: 1200px; height: 630px; overflow: hidden; background: #0d0b26; }
</style></head><body>
<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630" viewBox="0 0 1200 630">
  <defs>${gradient('g')}${nightSky(1200, 630)}</defs>
  <rect width="1200" height="630" fill="url(#skyBase)"/>
  <rect width="1200" height="630" fill="url(#sky1)"/>
  <rect width="1200" height="630" fill="url(#sky2)"/>
  ${stars(140, 1200, 630)}
  <circle cx="1020" cy="132" r="150" fill="url(#halo)"/>
  <circle cx="1020" cy="132" r="78" fill="url(#moon)"/>
  <g fill="#e9bf7c" opacity=".35"><circle cx="996" cy="112" r="12"/><circle cx="1046" cy="150" r="16"/><circle cx="1010" cy="170" r="7"/></g>
  <path d="M0 560 C 180 520, 320 548, 470 530 S 760 500, 900 528 S 1110 546, 1200 520 L1200 630 L0 630 Z" fill="#07061a" opacity=".85"/>
  <g transform="translate(96 150) scale(2.6)">${wolf()}</g>
  <text x="430" y="300" font-family="Unbounded" font-weight="700" font-size="104" fill="url(#glow)" letter-spacing="-2">Howlers</text>
  <text x="434" y="372" font-family="Inter" font-weight="500" font-size="40" fill="#c9c4ee">Find your pack &amp; howl together</text>
  <text x="434" y="440" font-family="Inter" font-weight="400" font-size="26" fill="#a9a3d6">Communities · live chat · polls · podcasts</text>
</svg></body></html>`;

const tile = (size, { rounded, safe }) => {
  // The wolf grid is 100×124; fit it into `safe` of the tile and centre it.
  const box = size * safe;
  const scale = box / 124;
  const x = (size - 100 * scale) / 2;
  const y = (size - 124 * scale) / 2;
  const r = rounded ? size * 0.21 : 0;
  return `<!doctype html><html><head><style>html,body{margin:0;background:transparent}</style></head><body>
<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
  <defs>${gradient('g')}<radialGradient id="bg" cx="50%" cy="30%" r="80%"><stop offset="0" stop-color="#1b1446"/><stop offset="1" stop-color="#0a0716"/></radialGradient></defs>
  <rect width="${size}" height="${size}" rx="${r}" fill="url(#bg)"/>
  <g transform="translate(${x.toFixed(2)} ${y.toFixed(2)}) scale(${scale.toFixed(4)})">${wolf()}</g>
</svg></body></html>`;
};

const badge = (size) => {
  const scale = (size * 0.9) / 124;
  const x = (size - 100 * scale) / 2;
  const y = (size - 124 * scale) / 2;
  return `<!doctype html><html><head><style>html,body{margin:0;background:transparent}</style></head><body>
<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
  <g transform="translate(${x.toFixed(2)} ${y.toFixed(2)}) scale(${scale.toFixed(4)})">${wolf('#ffffff')}</g>
</svg></body></html>`;
};

const jobs = [
  { out: 'public/og/howlers.png', html: ogCard, w: 1200, h: 630, transparent: false },
  { out: 'public/icons/icon-192.png', html: tile(192, { rounded: true, safe: 0.72 }), w: 192, h: 192, transparent: true },
  { out: 'public/icons/icon-512.png', html: tile(512, { rounded: true, safe: 0.72 }), w: 512, h: 512, transparent: true },
  // Maskable: launchers crop to a circle or squircle, so the wolf stays within the central 80% (here 62%).
  { out: 'public/icons/maskable-512.png', html: tile(512, { rounded: false, safe: 0.62 }), w: 512, h: 512, transparent: false },
  { out: 'public/icons/apple-touch-icon.png', html: tile(180, { rounded: false, safe: 0.7 }), w: 180, h: 180, transparent: false },
  { out: 'public/icons/badge-72.png', html: badge(72), w: 72, h: 72, transparent: true },
];

const browser = await chromium.launch();
try {
  for (const j of jobs) {
    const page = await browser.newPage({ viewport: { width: j.w, height: j.h }, deviceScaleFactor: 1 });
    // Fonts load from node_modules via file:// URLs, so the page itself must be a file:// document.
    const tmp = resolve(root, 'node_modules/.tmp', `brand-${j.w}x${j.h}.html`);
    mkdirSync(dirname(tmp), { recursive: true });
    writeFileSync(tmp, j.html);
    await page.goto(pathToFileURL(tmp).href);
    await page.evaluate(() => document.fonts.ready);
    const png = await page.screenshot({ type: 'png', omitBackground: j.transparent, clip: { x: 0, y: 0, width: j.w, height: j.h } });
    mkdirSync(dirname(resolve(root, j.out)), { recursive: true });
    writeFileSync(resolve(root, j.out), png);
    await page.close();
    console.log(`wrote ${j.out} (${j.w}×${j.h}, ${png.length} bytes)`);
  }
} finally {
  await browser.close();
}
