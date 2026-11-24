import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end suite (see docs/FRONTEND.md, "End-to-end tests").
 *
 * It expects a running backend (with the demo seed) and a frontend dev/preview server that proxies /api and /ws.
 * By default nothing is started for you: point E2E_BASE_URL at your server (default http://localhost:5174).
 * Set E2E_START_SERVER=1 to let Playwright start `vite --port <port>` itself.
 *
 * Tests share one database and log in as the seeded demo users, so they run serially in one worker:
 * a user's refresh token rotates on every page boot, and two contexts racing on the same token would trip reuse
 * detection. Every test creates uniquely named data, so repeated runs on the same database are fine.
 */
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5174';
const port = new URL(baseURL).port || '5174';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: process.env.E2E_START_SERVER
    ? {
        command: `npx vite --port ${port} --strictPort`,
        url: baseURL,
        reuseExistingServer: true,
        timeout: 120_000,
      }
    : undefined,
});
