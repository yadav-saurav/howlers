import type { Page } from '@playwright/test';
import { expect, test, uid } from './fixtures';

/** Flow 18: upload a private file, share it, open the link signed out. Plus an end-to-end encrypted round trip. */

async function upload(page: Page, name: string, content: string) {
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('region', { name: 'Upload files' }).getByRole('button', { name: 'choose files' }).click();
  await (await chooser).setFiles({ name, mimeType: 'text/plain', buffer: Buffer.from(content) });
}

test('18 · upload a file and share it with a link', async ({ sessions, browser }) => {
  const name = `e2e-notes-${uid()}.txt`;
  const content = `Shared by the end-to-end suite at ${new Date().toISOString()}\n`;
  const page = await sessions.open('mira_k', '/files');
  await upload(page, name, content);

  const row = page.getByRole('region', { name: 'My files' }).getByRole('listitem').filter({ hasText: name });
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: `Share ${name}` }).click();
  const dialog = page.getByRole('dialog', { name: 'Share a link' });
  await dialog.getByRole('button', { name: 'Create link' }).click();
  const ready = page.getByRole('dialog', { name: 'Link ready' });
  const link = await ready.getByRole('textbox', { name: 'Share link' }).inputValue();
  expect(link).toMatch(/\/s\/[A-Za-z0-9_-]+$/);
  await ready.getByRole('button', { name: 'Done' }).click();
  await expect(row.getByRole('button', { name: '1 active link' })).toBeVisible();

  // A visitor who isn't signed in.
  const visitor = await browser.newContext();
  try {
    const v = await visitor.newPage();
    await v.goto(link);
    await expect(v.getByRole('heading', { level: 1, name })).toBeVisible();
    const download = v.waitForEvent('download');
    await v.getByRole('button', { name: 'Download', exact: true }).click();
    const file = await download;
    expect(file.suggestedFilename()).toBe(name);
    const chunks: Buffer[] = [];
    for await (const c of (await file.createReadStream()) as AsyncIterable<Buffer>) chunks.push(c);
    expect(Buffer.concat(chunks).toString('utf8')).toBe(content);
  } finally {
    await visitor.close();
  }
});

test('18b · end-to-end encrypted upload, share and decrypt in a fresh browser', async ({ sessions, browser }) => {
  const name = `e2ee-secret-${uid()}.txt`;
  const content = `Only the link holder can read this. ${uid()}\n`;
  const page = await sessions.open('mira_k', '/files');
  const uploads: string[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes('/me/files')) uploads.push(r.postDataBuffer()?.toString('latin1') ?? '');
  });

  await page.getByRole('switch', { name: /Encrypt in browser/ }).click();
  await expect(page.getByRole('switch', { name: /Encrypt in browser/ })).toHaveAttribute('aria-checked', 'true');
  await upload(page, name, content);

  // After an encrypted upload the share dialog opens with the recovery key.
  const dialog = page.getByRole('dialog', { name: 'Share a link' });
  await expect(dialog.getByRole('textbox', { name: 'Recovery key for this file' })).not.toHaveValue('');
  await dialog.getByRole('button', { name: 'Create link' }).click();
  const ready = page.getByRole('dialog', { name: 'Link ready' });
  const link = await ready.getByRole('textbox', { name: 'Share link' }).inputValue();
  expect(link).toMatch(/\/s\/[A-Za-z0-9_-]+#k=/);
  await ready.getByRole('button', { name: 'Done' }).click();

  // The server only ever received ciphertext: neither the name nor the content went up in the clear.
  expect(uploads.length).toBeGreaterThan(0);
  for (const body of uploads) {
    expect(body).not.toContain(content.trim());
    expect(body).not.toContain(name);
  }

  const visitor = await browser.newContext();
  try {
    const v = await visitor.newPage();
    // Keep decrypted blobs in the page instead of saving them to disk.
    await v.addInitScript(() => {
      const w = window as unknown as { __blobs: Blob[] };
      w.__blobs = [];
      const original = URL.createObjectURL.bind(URL);
      URL.createObjectURL = (obj: Blob | MediaSource) => {
        if (obj instanceof Blob) w.__blobs.push(obj);
        return original(obj);
      };
    });
    await v.goto(link);
    await expect(v.getByRole('heading', { level: 1, name: 'End-to-end encrypted file' })).toBeVisible();
    await v.getByRole('button', { name: 'Download and decrypt' }).click();
    await expect(v.getByRole('status').filter({ hasText: `Decrypted in your browser and saved as “${name}”.` })).toBeVisible();
    const decrypted = await v.evaluate(async () => {
      const blobs = (window as unknown as { __blobs: Blob[] }).__blobs;
      return blobs.length ? await blobs[blobs.length - 1].text() : null;
    });
    expect(decrypted).toBe(content);
  } finally {
    await visitor.close();
  }
});
