import { expect, test } from './fixtures';

/** Flows 13–15 on the seeded Rust vs Go thread (it has real comments and stance signals). */
const SEEDED_TITLE = "After a year maintaining services in both Rust and Go, here's where each one actually hurt";

async function openSeededThread(page: import('@playwright/test').Page) {
  await page.getByRole('link', { name: SEEDED_TITLE }).first().click();
  await expect(page.getByRole('heading', { level: 1, name: SEEDED_TITLE })).toBeVisible();
}

test('13 · run Context Mode on a thread', async ({ sessions }) => {
  const page = await sessions.open('nadia_sec', '/search?q=Rust%20Go%20services');
  await openSeededThread(page);
  await page.getByRole('tab', { name: 'Context Mode' }).click();
  const panel = page.getByRole('tabpanel');
  await expect(panel.getByRole('img', { name: 'Distribution of comment stances' })).toBeVisible();
  await expect(panel.getByRole('heading', { name: 'Supporting arguments' })).toBeVisible();
  await expect(panel.getByRole('heading', { name: 'Counterarguments' })).toBeVisible();
  await expect(panel.getByRole('heading', { name: 'Discussion map' })).toBeVisible();
});

test('14 · generate a thread digest', async ({ sessions }) => {
  const page = await sessions.open('nadia_sec', '/search?q=Rust%20Go%20services');
  await openSeededThread(page);
  await page.getByRole('tab', { name: 'Digest' }).click();
  const panel = page.getByRole('tabpanel');
  await expect(panel.getByRole('heading', { name: 'Thread digest' })).toBeVisible();
  await expect(panel.getByText(/^Generated .* from \d+ comments/)).toBeVisible();
  await expect(panel.getByRole('heading', { name: 'Original question' })).toBeVisible();
  await expect(panel.getByRole('heading', { name: 'Summary' })).toBeVisible();

  // Regenerate on demand.
  const refreshed = page.waitForResponse((r) => r.url().includes('/digest') && r.request().method() === 'POST');
  await panel.getByRole('button', { name: 'Refresh digest' }).click();
  expect((await refreshed).status()).toBeLessThan(400);
  await expect(page.getByText('Digest refreshed')).toBeVisible();
});

