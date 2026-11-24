import { expect, test, uid } from './fixtures';

/** Flows 22–23: report → remove from the mod queue → author sees it; a direct message that arrives live. */

test('22 · moderate reported content', async ({ sessions }) => {
  const id = uid();
  const reason = `Spam: advertising (${id})`;
  const author = await sessions.api('dev_ostrander');
  const post = await author.createPost('programming', `E2E moderation ${id}: buy cheap followers here`);

  // A member reports it.
  const reporter = await sessions.open('nadia_sec', post.path);
  await reporter.getByRole('article', { name: post.title }).getByRole('button', { name: 'More actions' }).click();
  await reporter.getByRole('menuitem', { name: 'Report' }).click();
  const report = reporter.getByRole('dialog', { name: 'Report post' });
  await report.getByRole('radio', { name: /spam/i }).first().click();
  await report.getByRole('textbox', { name: /Details/ }).fill('Advertising, not discussion.');
  await report.getByRole('button', { name: 'Submit report' }).click();
  await expect(report).toBeHidden();

  // The moderator removes it from the queue.
  const mod = await sessions.open('mira_k', '/c/programming/mod');
  await expect(mod.getByRole('heading', { level: 1, name: 'Mod tools' })).toBeVisible();
  const item = mod.getByRole('article', { name: post.title });
  await expect(item).toBeVisible();
  await expect(item.getByText('Advertising, not discussion.')).toBeVisible();
  await item.getByRole('button', { name: 'Remove' }).click();
  const confirm = mod.getByRole('dialog', { name: 'Remove this post?' });
  await confirm.getByRole('textbox', { name: 'Reason' }).fill(reason);
  await confirm.getByRole('button', { name: 'Remove' }).click();
  await expect(mod.getByText('Removed', { exact: true }).first()).toBeVisible();
  await expect(item).toBeHidden();

  // The author is told, and the post says so.
  const view = await sessions.open('dev_ostrander', '/notifications');
  const notice = view.getByRole('button', { name: new RegExp(`Your post in c/programming was removed by moderators: Spam: advertising \\(${id}\\)`) });
  await expect(notice).toBeVisible();
  await notice.click();
  await expect(view).toHaveURL(/\/mod-actions\/\d+/);
  await expect(view.getByRole('heading', { level: 1, name: 'Your post was removed by moderators' })).toBeVisible();
  await expect(view.getByText(reason).first()).toBeVisible();
  await expect(view.getByText(post.title).first()).toBeVisible();
  // Decisions are the team's (D-014): the individual moderator isn't named to the author.
  await expect(view.getByRole('main')).not.toContainText('mira_k');

  await view.goto(post.path);
  await expect(view.getByText('This post was removed by moderators.')).toBeVisible();
});

test('23 · send a private message that arrives live', async ({ sessions }) => {
  // Open both inboxes on the same conversation first; the message must arrive without a reload.
  const sender = await sessions.api('mira_k');
  const conv = await sender.post<{ id: number }>('/me/conversations', { username: 'dev_ostrander' });

  const receiver = await sessions.open('dev_ostrander', `/messages/${conv.id}`);
  await expect(receiver.getByRole('textbox', { name: 'Message' })).toBeVisible();

  const page = await sessions.open('mira_k', '/messages');
  await page.getByRole('button', { name: 'Start a new message', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'New message' });
  await dialog.getByRole('textbox', { name: 'Username' }).fill('dev_ost');
  await dialog.getByRole('list', { name: 'Matching people' }).getByRole('button', { name: /u\/dev_ostrander/ }).click();
  await expect(page).toHaveURL(new RegExp(`/messages/${conv.id}$`));

  let reloaded = false;
  receiver.on('load', () => (reloaded = true));

  const text = `Live check ${uid()}: did this arrive without a refresh?`;
  const box = page.getByRole('textbox', { name: 'Message' });
  await box.fill(text);
  await box.press('Enter');
  await expect(page.getByRole('log', { name: /^Conversation with / }).getByText(text)).toBeVisible();

  // Delivered over the WebSocket into the receiver's open thread (a live region), with no reload.
  await expect(receiver.getByRole('log', { name: /^Conversation with / }).getByText(text)).toBeVisible({ timeout: 15_000 });
  expect(reloaded).toBe(false);
});
