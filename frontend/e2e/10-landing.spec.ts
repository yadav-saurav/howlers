import { expect, test } from '@playwright/test';

/** The public Howlers landing page: copy, CTAs, the FAQ search, and that reduced motion leaves everything visible. */

test('landing: headline, tagline and CTAs', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Let’s howl.');
  await expect(page.getByText('Find your pack & howl together').first()).toBeVisible();
  const main = page.getByRole('main');
  await expect(main.getByRole('link', { name: 'Join the pack' }).first()).toHaveAttribute('href', '/register');
  await expect(main.getByRole('link', { name: 'Explore Howlers' }).first()).toHaveAttribute('href', '/explore');
  await expect(page.getByRole('link', { name: 'Howlers home' })).toBeVisible();
  await expect(page.getByRole('contentinfo')).toContainText('Howlers.');
});

test('landing: FAQ search filters real answers', async ({ page }) => {
  await page.goto('/');
  const search = page.getByRole('searchbox', { name: 'Search the FAQ' });
  await search.scrollIntoViewIfNeeded();
  await search.fill('encrypted');
  const q = page.getByRole('button', { name: 'Are my messages end-to-end encrypted?' });
  await expect(q).toBeVisible();
  await expect(page.getByRole('button', { name: 'What is Pulse?' })).toHaveCount(0);
  await q.click();
  await expect(q).toHaveAttribute('aria-expanded', 'true');
  await expect(page.getByText('not end-to-end encrypted')).toBeVisible();
  await search.fill('zzzz-no-match');
  await expect(page.getByText(/No answer matches/)).toBeVisible();
});

test('landing: reduced motion shows everything without the intro', async ({ browser }) => {
  const ctx = await browser.newContext({ reducedMotion: 'reduce' });
  const page = await ctx.newPage();
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  const root = page.locator('.hl');
  await expect(root).not.toHaveClass(/intro/);
  await expect(root).not.toHaveClass(/rv-on/);
  const closing = page.getByRole('heading', { level: 2, name: /Come howl/ });
  await closing.scrollIntoViewIfNeeded();
  await expect(closing).toBeVisible();
  await ctx.close();
});
