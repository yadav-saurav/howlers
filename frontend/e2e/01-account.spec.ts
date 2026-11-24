import type { BrowserContext, Page } from '@playwright/test';
import { expect, test, uid } from './fixtures';

/**
 * Flows 1–4 with a brand-new account: register, complete onboarding, log out and back in, create a community.
 * Registration needs no email verification in this app (log-mode mail; verification is optional and nothing is gated
 * on it), so the suite never reads verification tokens. Registration is rate-limited to 5 per IP per hour, so the
 * suite registers exactly one account per run.
 */
test.describe.serial('account journey', () => {
  const id = uid();
  const username = `e2e_${id}`.slice(0, 21);
  const password = `pw-${id}-9x`;
  let context: BrowserContext;
  let page: Page;

  test.beforeAll(async ({ browser }) => {
    context = await browser.newContext();
    page = await context.newPage();
  });
  test.afterAll(async () => {
    await context.close();
  });

  test('1 · register a new account', async () => {
    await page.goto('/register');
    await expect(page.getByRole('heading', { name: 'Join Howlers' })).toBeVisible();
    await page.getByLabel('Username').fill(username);
    await expect(page.getByText(/available/i)).toBeVisible();
    await page.getByLabel('Email').fill(`${username}@example.test`);
    await page.getByLabel('Password').fill(password);
    await page.getByLabel(/Display name/).fill(`E2E ${id}`);
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page).toHaveURL(/\/onboarding$/);
  });

  test('3 · complete onboarding', async () => {
    await expect(page.getByRole('heading', { name: 'Let’s shape your feed' })).toBeVisible();
    await page.getByRole('button', { name: /^Programming/ }).click();
    await expect(page.getByRole('button', { name: /^Programming/ })).toHaveAttribute('aria-pressed', 'true');
    await page.getByRole('button', { name: 'Continue' }).click();

    await expect(page.getByRole('heading', { name: 'Join some communities' })).toBeVisible();
    const programming = page.getByRole('button', { name: /c\/programming/ });
    await programming.click();
    await expect(programming).toHaveAttribute('aria-pressed', 'true');
    await page.getByRole('button', { name: 'Continue' }).click();

    await expect(page.getByRole('heading', { name: 'Follow people (optional)' })).toBeVisible();
    await page.getByRole('button', { name: 'Continue' }).click();

    await expect(page.getByRole('heading', { name: 'Choose a theme' })).toBeVisible();
    await page.getByRole('radio', { name: 'Light' }).click();
    await page.getByRole('button', { name: 'Finish' }).click();

    await expect(page).toHaveURL(/\/home$/);
    await expect(page.getByRole('heading', { name: 'Home feed' })).toBeVisible();
    await expect(page.locator('html')).not.toHaveClass(/dark/);
    // The joined community shows up in the left nav.
    await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'c/programming' })).toBeVisible();
  });

  test('2 · log out and log in again', async () => {
    await page.getByRole('button', { name: 'Account menu' }).click();
    await page.getByRole('menuitem', { name: 'Log out' }).click();
    await expect(page.getByRole('link', { name: 'Log in' })).toBeVisible();

    await page.goto('/login');
    await page.getByLabel('Username or email').fill(username);
    await page.getByLabel('Password').fill(password);
    await page.getByRole('button', { name: 'Log in' }).click();
    await expect(page).toHaveURL(/\/home$/);
    await expect(page.getByRole('button', { name: 'Account menu' })).toContainText(username);
  });

  test('4 · create a community', async () => {
    const slug = `e2e_${id}`.slice(0, 24);
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Create a community' }).click();
    await expect(page.getByRole('heading', { name: 'Create a community' })).toBeVisible();
    await page.getByLabel('Address').fill(slug);
    await page.getByLabel('Display name').fill(`E2E community ${id}`);
    await page.getByLabel('Description', { exact: true }).fill('A community created by the end-to-end suite to check the create flow.');
    await page.getByRole('combobox', { name: /Topic/ }).selectOption({ label: 'Programming' });
    await page.getByRole('button', { name: 'Create community' }).click();

    await expect(page).toHaveURL(new RegExp(`/c/${slug}$`));
    await expect(page.getByRole('heading', { level: 1, name: `c/${slug}` })).toBeVisible();
    // The creator owns it: moderator tools are offered.
    await expect(page.getByRole('link', { name: 'Mod tools' })).toBeVisible();
  });
});
