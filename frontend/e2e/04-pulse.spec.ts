import { expect, test, uid } from './fixtures';

/** Flows 16–17: a moderator starts a Pulse, a member votes in one. Pulses last one hour so runs don't pile up. */

test('16 · create a Pulse', async ({ sessions }) => {
  const question = `E2E pulse ${uid()}: tabs or spaces this year?`;
  const page = await sessions.open('mira_k', '/c/programming?view=pulse');
  await page.getByRole('button', { name: 'New Pulse' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('radio', { name: 'Poll' }).click();
  await dialog.getByRole('textbox', { name: 'Question' }).fill(question);
  await dialog.getByRole('textbox', { name: 'Option 1' }).fill('Tabs');
  await dialog.getByRole('textbox', { name: 'Option 2' }).fill('Spaces');
  await dialog.getByRole('combobox', { name: 'Duration' }).selectOption({ label: '1 hour' });
  await dialog.getByRole('button', { name: 'Start Pulse' }).click();
  await expect(dialog).toBeHidden();

  const card = page.getByRole('main').getByRole('article', { name: question });
  await expect(card).toBeVisible();
  await expect(card.getByRole('radio', { name: 'Tabs' })).toBeVisible();
});

test('17 · vote in a Pulse', async ({ sessions }) => {
  const question = `E2E pulse ${uid()}: will you try the new linter?`;
  const api = await sessions.api('mira_k');
  await api.post('/communities/programming/pulses', {
    type: 'POLL', title: question, options: ['Yes', 'Not yet'], multiple: false, anonymous: false, allowChange: true, durationHours: 1,
  });

  const page = await sessions.open('dev_ostrander', '/c/programming?view=pulse');
  const card = page.getByRole('main').getByRole('article', { name: question });
  // Results stay hidden until you vote.
  await expect(card.getByRole('img', { name: /Yes: \d+%/ })).toHaveCount(0);
  await card.getByRole('radio', { name: 'Yes' }).click();
  await card.getByRole('button', { name: 'Vote', exact: true }).click();

  await expect(page.getByText('Vote recorded')).toBeVisible();
  await expect(card.getByText('Your vote: Yes')).toBeVisible();
  await expect(card.getByRole('img', { name: 'Yes: 100%, Not yet: 0%' })).toBeVisible();
});
