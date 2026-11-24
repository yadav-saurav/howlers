import { expect, test } from './fixtures';

/** Keyboard checks axe can't make: skip link, visible focus, dialog focus trap and focus return. */

test('skip link moves focus to the main content', async ({ sessions }) => {
  const page = await sessions.open('paperback_jo', '/home');
  await expect(page.getByRole('heading', { level: 1, name: 'Home feed' })).toBeVisible();
  await page.keyboard.press('Tab');
  const skip = page.getByRole('link', { name: 'Skip to content' });
  await expect(skip).toBeFocused();
  await expect(skip).toBeInViewport();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
  // The next Tab lands inside the main content, not back in the header or nav.
  await page.keyboard.press('Tab');
  expect(await page.evaluate(() => !!document.activeElement?.closest('main'))).toBe(true);
});

test('keyboard focus is visible', async ({ sessions }) => {
  const page = await sessions.open('paperback_jo', '/home');
  await expect(page.getByRole('heading', { level: 1, name: 'Home feed' })).toBeVisible();
  for (let i = 0; i < 6; i++) {
    await page.keyboard.press('Tab');
    const ring = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body) return 'none';
      const s = getComputedStyle(el);
      const outline = s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0;
      const shadow = s.boxShadow && s.boxShadow !== 'none';
      return outline || shadow ? 'visible' : `${el.tagName} ${el.getAttribute('aria-label') ?? el.textContent?.trim().slice(0, 30)}`;
    });
    expect(ring, 'focused element shows a focus indicator').toBe('visible');
  }
});

test('dialogs trap focus, close on Escape and return focus', async ({ sessions }) => {
  const page = await sessions.open('paperback_jo', '/messages');
  const opener = page.getByRole('main').getByRole('button', { name: 'Start a new message', exact: true });
  await opener.focus();
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog', { name: 'New message' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('textbox', { name: 'Username' })).toBeFocused();
  for (let i = 0; i < 8; i++) {
    await page.keyboard.press('Tab');
    expect(await dialog.evaluate((d) => d.contains(document.activeElement))).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(opener).toBeFocused();
});

test('menus close on Escape and return focus to their button', async ({ sessions }) => {
  const page = await sessions.open('paperback_jo', '/home');
  await expect(page.getByRole('article').first()).toBeVisible();
  const account = page.getByRole('button', { name: /^Account menu/ });
  await account.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('menu')).toBeVisible();
  await expect(page.getByRole('menuitem').first()).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('menuitem').nth(1)).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('menu')).toBeHidden();
  await expect(account).toBeFocused();

  const more = page.getByRole('main').getByRole('button', { name: 'More actions' }).first();
  await more.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('menu')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('menu')).toBeHidden();
  await expect(more).toBeFocused();
});

test('search suggestions are reachable with arrows and close on Escape', async ({ sessions }) => {
  const page = await sessions.open('paperback_jo', '/home');
  const box = page.getByRole('combobox', { name: 'Search Howlers' });
  await box.focus();
  await page.keyboard.type('rust');
  await expect(page.getByRole('listbox')).toBeVisible();
  await page.keyboard.press('ArrowDown');
  await expect(box).toHaveAttribute('aria-activedescendant', /.+/);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toBeHidden();
  await expect(box).toBeFocused();
});

test('mobile navigation drawer closes on Escape and returns focus to its button', async ({ sessions }) => {
  const { page } = await sessions.context('paperback_jo');
  await page.setViewportSize({ width: 375, height: 812 });
  try {
    await sessions.open('paperback_jo', '/home');
    const open = page.getByRole('button', { name: 'Open navigation' });
    await open.focus();
    await page.keyboard.press('Enter');
    const drawer = page.getByRole('dialog');
    await expect(drawer).toBeVisible();
    expect(await drawer.evaluate((d) => d.contains(document.activeElement))).toBe(true);
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    await expect(open).toBeFocused();
  } finally {
    await page.setViewportSize({ width: 1280, height: 720 });
  }
});

test('newer pages keep the skip link and a single main landmark', async ({ sessions }) => {
  for (const path of ['/files', '/leaderboard', '/podcasts', '/c/programming/mod?tab=held']) {
    const page = await sessions.open('mira_k', path);
    await expect(page.getByRole('main')).toHaveCount(1);
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: 'Skip to content' }), path).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('main')).toBeFocused();
  }
});
