import { expect, test } from './fixtures';

/** Flow 21 (podcasts and moderator funding, flows 19–20, were retired). */

test('21 · view the leaderboard', async ({ sessions }) => {
  const page = await sessions.open('paperback_jo', '/leaderboard');
  await expect(page.getByRole('heading', { level: 1, name: 'Leaderboard' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Moderators' })).toHaveAttribute('aria-selected', 'true');
  const ranking = page.getByRole('list', { name: /^Moderator ranking for / });
  const empty = page.getByRole('heading', { name: /^No ranked moderators for / });
  // Scores come from real moderation activity only: a fresh database (CI) can honestly have nobody ranked yet.
  await expect(ranking.or(empty)).toBeVisible();
  if (await ranking.isVisible()) {
    const row = ranking.getByRole('listitem').first();
    await expect(row.getByRole('link', { name: /^u\// })).toBeVisible();
    // The score breakdown opens from a disclosure button and explains the formula.
    const toggle = row.getByRole('button', { name: /^Score breakdown for u\// });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(row.getByText(/Consistency/).first()).toBeVisible();
  }
  // The formula is always public.
  await expect(page.getByRole('heading', { name: 'How the score works' })).toBeVisible();

  await page.getByRole('tab', { name: 'Topics' }).click();
  await expect(page.getByRole('tab', { name: 'Topics' })).toHaveAttribute('aria-selected', 'true');
});
