import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { expect, test, uid, type DemoUser, type Sessions } from './fixtures';

/**
 * axe-core audit of the key pages in both themes, at desktop width and at 375 px.
 * Serious and critical violations fail the test; see docs/ACCESSIBILITY.md for the method and anything accepted.
 */

type Target = {
  name: string;
  /** A fixed path, or a function that arranges test data through the API and returns the path. */
  path: string | ((sessions: Sessions) => Promise<string>);
  user: DemoUser | null;
  ready: (page: Page) => Promise<void>;
};

/** A small private file owned by mira_k with a fresh share link; the share page is then audited signed out. */
async function shareLinkPath(sessions: Sessions): Promise<string> {
  const api = await sessions.api('mira_k');
  const name = `axe-share-${uid()}.txt`;
  const file = await api.upload<{ id: number }>('/me/files', { name, mimeType: 'text/plain', buffer: Buffer.from('Accessibility check of the share page.\n') });
  const { token } = await api.post<{ token: string }>(`/me/files/${file.id}/links`, {});
  return `/s/${token}`;
}

/** The demo pool in c/programming (seeded), falling back to whichever pool the community has. */
async function poolReportPath(sessions: Sessions): Promise<string> {
  const api = await sessions.api('mira_k');
  const f = await api.get<{ pools: { id: number; title: string }[] }>('/communities/programming/funding');
  const pool = f.pools.find((p) => p.title.includes('(demo)')) ?? f.pools[0];
  expect(pool, 'c/programming has a funding pool').toBeTruthy();
  return `/funding/pools/${pool.id}`;
}

const h1 = (name: string | RegExp) => (p: Page) => expect(p.getByRole('heading', { level: 1, name })).toBeVisible();
const selectedTab = (name: string) => (p: Page) => expect(p.getByRole('tab', { name, selected: true })).toBeVisible();

const SEEDED_POST = "After a year maintaining services in both Rust and Go, here's where each one actually hurt";

const targets: Target[] = [
  { name: 'landing', path: '/', user: null, ready: (p) => expect(p.getByRole('heading', { level: 1 })).toBeVisible() },
  { name: 'home feed', path: '/home', user: 'mira_k', ready: (p) => expect(p.getByRole('article').first()).toBeVisible() },
  { name: 'post page', path: '/c/programming/p/1', user: 'mira_k', ready: (p) => expect(p.getByRole('heading', { level: 1, name: SEEDED_POST })).toBeVisible() },
  { name: 'community page', path: '/c/programming', user: 'mira_k', ready: (p) => expect(p.getByRole('article').first()).toBeVisible() },
  { name: 'messages', path: '/messages', user: 'mira_k', ready: (p) => expect(p.getByRole('heading', { level: 1, name: 'Messages' })).toBeVisible() },
  { name: 'files', path: '/files', user: 'mira_k', ready: (p) => expect(p.getByRole('region', { name: 'My files' })).toBeVisible() },
  { name: 'leaderboard', path: '/leaderboard', user: 'mira_k', ready: (p) => expect(p.getByRole('heading', { level: 1, name: 'Leaderboard' })).toBeVisible() },
  { name: 'settings', path: '/settings', user: 'mira_k', ready: (p) => expect(p.getByRole('heading', { level: 1 })).toBeVisible() },
  { name: 'funding', path: '/funding', user: 'mira_k', ready: (p) => expect(p.getByRole('heading', { level: 1, name: 'Moderator funding' })).toBeVisible() },
  { name: 'admin', path: '/admin', user: 'admin_demo', ready: (p) => expect(p.getByRole('heading', { level: 1 })).toBeVisible() },
  // Pages added after the first audit (round 3).
  { name: 'share page (signed out)', path: shareLinkPath, user: null, ready: (p) => expect(p.getByRole('heading', { level: 1, name: /^axe-share-/ })).toBeVisible() },
  { name: 'share page missing link', path: '/s/not-a-real-token-000000', user: null, ready: (p) => expect(p.getByText('This link doesn’t exist')).toBeVisible() },
  { name: 'community funding', path: '/c/programming?view=funding', user: 'mira_k', ready: (p) => expect(p.getByText('Moderator tooling, this quarter (demo)').first()).toBeVisible() },
  { name: 'pool report', path: poolReportPath, user: 'mira_k', ready: h1('Transparency report') },
  { name: 'podcast studio', path: '/c/programming/podcasts/studio', user: 'mira_k', ready: h1(/Podcast studio/) },
  { name: 'podcasts', path: '/podcasts', user: 'mira_k', ready: h1(/Podcasts/) },
  { name: 'leaderboard topics', path: '/leaderboard?tab=topics', user: 'mira_k', ready: selectedTab('Topics') },
  { name: 'admin users', path: '/admin?tab=users', user: 'admin_demo', ready: selectedTab('Users') },
  { name: 'admin communities', path: '/admin?tab=communities', user: 'admin_demo', ready: selectedTab('Communities') },
  { name: 'admin flags', path: '/admin?tab=flags', user: 'admin_demo', ready: selectedTab('Flags') },
  { name: 'admin audit', path: '/admin?tab=audit', user: 'admin_demo', ready: selectedTab('Audit log') },
  { name: 'mod held', path: '/c/programming/mod?tab=held', user: 'mira_k', ready: selectedTab('Held') },
  { name: 'mod team', path: '/c/programming/mod?tab=team', user: 'mira_k', ready: selectedTab('Team') },
  { name: 'verify email missing token', path: '/verify-email', user: null, ready: (p) => expect(p.getByText('Link is incomplete')).toBeVisible() },
  { name: 'verify email invalid token', path: '/verify-email?token=not-a-real-token', user: null, ready: (p) => expect(p.getByText('Link not recognised')).toBeVisible() },
  { name: 'not found', path: '/no-such-page-here', user: 'mira_k', ready: h1('This trail goes nowhere') },
];

const VIEWPORTS = [
  { label: 'desktop', size: { width: 1280, height: 900 } },
  { label: 'mobile', size: { width: 375, height: 812 } },
] as const;
const THEMES = ['dark', 'light'] as const;

async function setTheme(page: Page, theme: 'dark' | 'light') {
  // The app's theme is the `dark` class on <html> (tokens.css); the signed-in user's saved preference is applied on boot,
  // so switch after load, exactly as the theme toggle does.
  // Transitions are switched off first so axe never samples a colour halfway through `transition-colors`.
  await page.addStyleTag({ content: '*, *::before, *::after { transition: none !important; animation: none !important; }' });
  await page.evaluate((t) => document.documentElement.classList.toggle('dark', t === 'dark'), theme);
}

for (const t of targets.filter((x) => !process.env.AXE_ONLY || process.env.AXE_ONLY.split(',').includes(x.name))) {
  test(`axe: ${t.name}`, async ({ sessions, browser }, testInfo) => {
    test.setTimeout(120_000);
    const anon = t.user ? null : await browser.newContext();
    const page = t.user ? (await sessions.context(t.user)).page : await anon!.newPage();
    const failures: string[] = [];
    const path = typeof t.path === 'string' ? t.path : await t.path(sessions);
    try {
      for (const vp of VIEWPORTS) {
        await page.setViewportSize(vp.size);
        if (t.user) await sessions.open(t.user, path);
        else await page.goto(path);
        await t.ready(page);
        // Let lazy rails, images and live counts settle.
        await page.waitForLoadState('networkidle').catch(() => {});
        for (const theme of THEMES) {
          await setTheme(page, theme);
          const results = await new AxeBuilder({ page })
            .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'])
            .analyze();
          const bad = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
          await testInfo.attach(`${t.name}-${vp.label}-${theme}.json`, {
            body: JSON.stringify(results.violations, null, 2),
            contentType: 'application/json',
          });
          for (const v of bad) {
            const where = v.nodes.slice(0, 4).map((n) => `${n.target.join(' ')} ${n.html.slice(0, 160)}${n.failureSummary ? ` (${n.failureSummary.split('\n').slice(1).join(' ').trim()})` : ''}`);
            failures.push(`[${vp.label}/${theme}] ${v.impact} ${v.id}: ${v.help} — ${v.nodes.length} node(s): ${where.join(' | ')}`);
          }
        }
      }
    } finally {
      await page.setViewportSize({ width: 1280, height: 720 });
      await anon?.close();
    }
    expect(failures, failures.join('\n')).toEqual([]);
  });
}
