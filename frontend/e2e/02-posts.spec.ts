import { expect, test, uid } from './fixtures';

/** Flows 5–12: join, create a post, vote, comment, reply, save, collect into a collection, search. */

test('5 · join a community', async ({ sessions }) => {
  const page = await sessions.open('paperback_jo', '/c/science');
  await expect(page.getByRole('heading', { level: 1, name: 'c/science' })).toBeVisible();
  // Repeatable on one database: leave first if a previous run joined.
  const joined = page.getByRole('button', { name: /^(Joined|Leave)/ });
  if (await joined.isVisible()) {
    await joined.click();
    const confirm = page.getByRole('dialog').getByRole('button', { name: /Leave/ });
    if (await confirm.isVisible().catch(() => false)) await confirm.click();
  }
  await page.getByRole('button', { name: 'Join', exact: true }).click();
  await expect(page.getByRole('button', { name: /^(Joined|Leave)/ })).toBeVisible();

  // Membership is real: it survives a reload and the community appears in the nav.
  await page.reload();
  await expect(page.getByRole('button', { name: /^(Joined|Leave)/ })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'c/science' }).first()).toBeVisible();
});

test('6 · create a text post', async ({ sessions }) => {
  const title = `E2E post ${uid()}: what made your last refactor worth it?`;
  const page = await sessions.open('mira_k', '/c/programming/submit');
  await expect(page.getByRole('heading', { name: 'Create a post' })).toBeVisible();
  await expect(page.getByRole('combobox', { name: 'Community' })).toHaveValue(/programming/);
  await page.getByRole('textbox', { name: 'Title' }).fill(title);
  await page.getByRole('textbox', { name: 'Post body' }).click();
  await page.keyboard.type('Written by the end-to-end suite. Tests that pin behaviour made ours safe.');
  await page.getByRole('button', { name: 'Publish' }).click();

  await expect(page).toHaveURL(/\/c\/programming\/p\/\d+/);
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
  await expect(page.getByText('Written by the end-to-end suite.')).toBeVisible();
});

test.describe('on an existing post', () => {
  let post: { id: number; title: string; path: string };

  test.beforeAll(async ({ sessions }) => {
    const api = await sessions.api('mira_k');
    post = await api.createPost('programming', `E2E thread ${uid()}: favourite debugging habit?`);
  });

  test('7 · vote on a post', async ({ sessions }) => {
    const page = await sessions.open('dev_ostrander', post.path);
    const votes = page.getByRole('group', { name: `Vote on ${post.title}` });
    const before = Number(await votes.getByText(/^-?\d+$/).textContent());
    await votes.getByRole('button', { name: 'Upvote' }).click();
    await expect(votes.getByRole('button', { name: 'Upvote' })).toHaveAttribute('aria-pressed', 'true');
    await expect(votes.getByText(String(before + 1), { exact: true })).toBeVisible();

    await page.reload();
    await expect(votes.getByRole('button', { name: 'Upvote' })).toHaveAttribute('aria-pressed', 'true');
    await expect(votes.getByText(String(before + 1), { exact: true })).toBeVisible();
  });

  test('8 · comment on a post', async ({ sessions }) => {
    const text = `Print the state before you change it. (${uid()})`;
    const page = await sessions.open('dev_ostrander', post.path);
    await page.getByRole('textbox', { name: 'Comment' }).fill(text);
    await page.getByRole('button', { name: 'Comment', exact: true }).click();
    const comment = page.getByRole('article', { name: 'Comment by dev_ostrander' }).filter({ hasText: text });
    await expect(comment).toBeVisible();
    await page.reload();
    await expect(comment).toBeVisible();
  });

  test('9 · reply to a comment', async ({ sessions }) => {
    const api = await sessions.api('dev_ostrander');
    const parentText = `Bisect first, theorise later. (${uid()})`;
    await api.post(`/posts/${post.id}/comments`, { body: parentText });

    const reply = `Agreed, and keep the failing input as a test. (${uid()})`;
    const page = await sessions.open('nadia_sec', post.path);
    const parent = page.getByRole('article', { name: 'Comment by dev_ostrander' }).filter({ hasText: parentText });
    await parent.getByRole('button', { name: 'Reply' }).click();
    await page.getByRole('textbox', { name: 'Reply' }).fill(reply);
    await parent.getByRole('button', { name: 'Reply', exact: true }).last().click();

    const child = page.getByRole('article', { name: 'Comment by nadia_sec' }).filter({ hasText: reply });
    await expect(child).toBeVisible();
    await page.reload();
    await expect(child).toBeVisible();
  });

  test('10 · save a post', async ({ sessions }) => {
    const page = await sessions.open('dev_ostrander', post.path);
    const article = page.getByRole('article', { name: post.title });
    const save = article.getByRole('button', { name: /^(Save|Saved)$/ });
    if ((await save.getAttribute('aria-pressed')) === 'true') await save.click();
    await expect(save).toHaveAttribute('aria-pressed', 'false');
    await save.click();
    await expect(save).toHaveAttribute('aria-pressed', 'true');

    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Saved' }).click();
    await expect(page).toHaveURL(/\/saved$/);
    await expect(page.getByRole('link', { name: post.title })).toBeVisible();
  });

  test('11 · create a collection and file the post', async ({ sessions }) => {
    const name = `E2E ${uid()}`;
    const page = await sessions.open('dev_ostrander', post.path);
    await page.getByRole('article', { name: post.title }).getByRole('button', { name: 'More actions' }).click();
    await page.getByRole('menuitem', { name: 'Save to collection' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('textbox', { name: 'New collection name' }).fill(name);
    await dialog.getByRole('button', { name: 'Create' }).click();
    await expect(page.getByText(`Created ${name} and saved`)).toBeVisible();
    await page.keyboard.press('Escape');

    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Library' }).click();
    await page.getByRole('button', { name: new RegExp(`^${name}`) }).click();
    await expect(page.getByRole('link', { name: post.title })).toBeVisible();
  });

  test('12 · search finds the post', async ({ sessions }) => {
    const page = await sessions.open('dev_ostrander', '/home');
    const box = page.getByRole('combobox', { name: 'Search Howlers' });
    await box.fill(post.title);
    await box.press('Enter');
    await expect(page).toHaveURL(/\/search\?/);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Results for');
    await expect(page.getByRole('link', { name: post.title })).toBeVisible();
  });
});
