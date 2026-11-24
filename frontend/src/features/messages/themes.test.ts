import { describe, expect, it } from 'vitest';
import { CHAT_THEMES, DEFAULT_THEME_ID, THEME_IDS, contrastRatio, getTheme, isThemeId, luminance, themeVars } from './themes';

const CONTRACT_IDS = ['classic', 'midnight', 'ocean', 'sunset', 'forest', 'candy', 'neon', 'lavender', 'mono', 'aurora', 'ember', 'sakura'];

describe('chat theme registry', () => {
  it('has exactly the 12 ids the API accepts, each once', () => {
    expect([...THEME_IDS].sort()).toEqual([...CONTRACT_IDS].sort());
    expect(new Set(THEME_IDS).size).toBe(12);
  });

  it('falls back to Classic for null, empty or unknown ids', () => {
    expect(getTheme(null).id).toBe(DEFAULT_THEME_ID);
    expect(getTheme('').id).toBe('classic');
    expect(getTheme('vaporwave').id).toBe('classic');
    expect(getTheme('sunset').name).toBe('Sunset');
    expect(isThemeId('ocean')).toBe(true);
    expect(isThemeId('Ocean')).toBe(false);
  });

  it('gives every theme a distinct look', () => {
    const looks = new Set(CHAT_THEMES.map((t) => `${t.light.bg}|${t.light.out.join()}|${t.light.accent}`));
    expect(looks.size).toBe(CHAT_THEMES.length);
    expect(new Set(CHAT_THEMES.map((t) => t.name)).size).toBe(CHAT_THEMES.length);
  });

  for (const t of CHAT_THEMES) {
    for (const mode of ['light', 'dark'] as const) {
      it(`${t.id} (${mode}) keeps bubble text at 4.5:1 or better`, () => {
        const p = t[mode];
        expect(contrastRatio(p.outFg, p.out[0])).toBeGreaterThanOrEqual(4.5);
        expect(contrastRatio(p.outFg, p.out[1])).toBeGreaterThanOrEqual(4.5);
        expect(contrastRatio(p.inFg, p.incoming)).toBeGreaterThanOrEqual(4.5);
        expect(contrastRatio(p.accentFg, p.accent)).toBeGreaterThanOrEqual(4.5);
        // Sender names and quote labels are drawn in the accent on incoming bubbles.
        expect(contrastRatio(p.accent, p.incoming)).toBeGreaterThanOrEqual(4.5);
        // ...and on the composer's quoted-reply bar (the app's surface-2).
        expect(contrastRatio(p.accent, mode === 'light' ? '#eaedef' : '#1a1f23')).toBeGreaterThanOrEqual(4.5);
      });
    }
  }

  it('exposes both palettes as CSS variables', () => {
    const v = themeVars(getTheme('neon')) as Record<string, string>;
    expect(v['--ct-out-1-l']).toBe('#6d28d9');
    expect(v['--ct-out-1-d']).toBe('#a21caf');
    expect(v['--ct-pattern-image']).toContain('var(--ct-pattern)');
  });
});

describe('contrast maths', () => {
  it('matches the WCAG reference values', () => {
    expect(luminance('#ffffff')).toBeCloseTo(1, 5);
    expect(luminance('#000000')).toBe(0);
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(contrastRatio('#777777', '#ffffff')).toBeCloseTo(4.48, 2);
  });
});
