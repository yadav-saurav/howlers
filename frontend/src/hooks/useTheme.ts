import { create } from 'zustand';
import { brand } from '@/design-system/brand';

export type ThemePref = 'dark' | 'light' | 'system';

function readStored(): ThemePref {
  try {
    const v = localStorage.getItem(brand.themeStorageKey);
    if (v === 'dark' || v === 'light' || v === 'system') return v;
  } catch {
    /* storage unavailable */
  }
  return 'dark';
}

export function resolveTheme(pref: ThemePref): 'dark' | 'light' {
  if (pref !== 'system') return pref;
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function apply(pref: ThemePref) {
  const resolved = resolveTheme(pref);
  document.documentElement.classList.toggle('dark', resolved === 'dark');
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', resolved === 'dark' ? '#0E0F11' : '#F7F6F3');
}

interface ThemeState {
  pref: ThemePref;
  setPref: (p: ThemePref) => void;
}

export const useTheme = create<ThemeState>((set) => ({
  pref: readStored(),
  setPref: (pref) => {
    try {
      localStorage.setItem(brand.themeStorageKey, pref);
    } catch {
      /* ignore */
    }
    apply(pref);
    set({ pref });
  },
}));

export function initTheme() {
  apply(useTheme.getState().pref);
  window.matchMedia?.('(prefers-color-scheme: light)').addEventListener?.('change', () => {
    if (useTheme.getState().pref === 'system') apply('system');
  });
}
