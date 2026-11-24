import { existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { test as base, expect, type Browser, type BrowserContext, type Page } from '@playwright/test';

/**
 * Where each demo user's latest refresh cookie is kept between local runs (git-ignored). It only spares the login
 * rate limit during repeated local runs; a stale or revoked cookie simply falls back to a fresh login.
 */
const AUTH_DIR = join(process.cwd(), 'e2e', '.auth');
const authFile = (user: DemoUser) => join(AUTH_DIR, `${user}.json`);

/** Seeded demo accounts (backend/src/main/resources/seed/demo.json and AdminDemoSeeder). */
export const DEMO_PASSWORD = 'howlers-demo-1';
export type DemoUser = 'mira_k' | 'dev_ostrander' | 'nadia_sec' | 'paperback_jo' | 'tunde_builds' | 'admin_demo';

/** A short unique suffix so every run creates fresh, non-colliding data on a shared database. */
export function uid(): string {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`;
}

/**
 * One logged-in browser context per demo user, created once per worker and kept for the worker's lifetime.
 *
 * Why not log in per test: the backend rate-limits logins (10 per account and 30 per IP per 10 minutes).
 * Why one long-lived page per user: the refresh cookie rotates on every page boot and reuse of a rotated token revokes
 * the session, so a user must never have two boots racing. `open()` reuses the same page and waits for each boot's
 * refresh to finish before returning.
 */
type Entry = { context: BrowserContext; page: Page; token: string; tokenAt: number };

export class Sessions {
  private contexts = new Map<string, Entry>();
  constructor(private browser: Browser, private baseURL: string) {}

  async context(user: DemoUser): Promise<Entry> {
    let entry = this.contexts.get(user);
    if (!entry) {
      // Reuse the refresh cookie a previous local run left behind (git-ignored), else log in.
      const saved = authFile(user);
      let context = await this.browser.newContext({ baseURL: this.baseURL, storageState: existsSync(saved) ? saved : undefined });
      let res = existsSync(saved) ? await context.request.post('/api/v1/auth/refresh') : null;
      if (!res || res.status() !== 200) {
        await context.close();
        context = await this.browser.newContext({ baseURL: this.baseURL });
        res = await context.request.post('/api/v1/auth/login', { data: { login: user, password: DEMO_PASSWORD } });
        expect(res.status(), `login as ${user}`).toBe(200);
      }
      const { accessToken } = (await res.json()) as { accessToken: string };
      const page = await context.newPage();
      entry = { context, page, token: accessToken, tokenAt: Date.now() };
      this.contexts.set(user, entry);
    }
    return entry;
  }

  /** Navigate the user's page to `path` with a full load and wait until the session has been restored. */
  async open(user: DemoUser, path: string): Promise<Page> {
    const { page } = await this.context(user);
    await gotoAuthed(page, path);
    return page;
  }

  /** JSON API calls as `user`, for arranging test data that isn't the flow under test. */
  async api(user: DemoUser): Promise<Api> {
    const entry = await this.context(user);
    if (Date.now() - entry.tokenAt > 10 * 60_000) {
      // The context's cookie jar is shared with its page, so the rotated refresh cookie stays consistent.
      const res = await entry.context.request.post('/api/v1/auth/refresh');
      expect(res.status(), `refresh for ${user}`).toBe(200);
      entry.token = ((await res.json()) as { accessToken: string }).accessToken;
      entry.tokenAt = Date.now();
    }
    return new Api(entry.context, entry.token);
  }

  async dispose() {
    mkdirSync(AUTH_DIR, { recursive: true });
    for (const [user, { context }] of this.contexts) {
      await context.storageState({ path: authFile(user as DemoUser) }).catch(() => {});
      await context.close().catch(() => {});
    }
    this.contexts.clear();
  }
}

export class Api {
  constructor(private context: BrowserContext, private token: string) {}

  private async call<T>(method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string, data?: unknown): Promise<T> {
    const res = await this.context.request.fetch(`/api/v1${path}`, {
      method,
      data,
      headers: { Authorization: `Bearer ${this.token}` },
    });
    if (!res.ok()) throw new Error(`${method} ${path} -> ${res.status()} ${await res.text()}`);
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }
  /** Multipart POST with one file and optional text fields. */
  async upload<T>(path: string, file: { name: string; mimeType: string; buffer: Buffer }, fields: Record<string, string> = {}): Promise<T> {
    const res = await this.context.request.post(`/api/v1${path}`, {
      multipart: { ...fields, file },
      headers: { Authorization: `Bearer ${this.token}` },
    });
    if (!res.ok()) throw new Error(`POST ${path} -> ${res.status()} ${await res.text()}`);
    return (await res.json()) as T;
  }
  get<T>(path: string) { return this.call<T>('GET', path); }
  post<T>(path: string, data?: unknown) { return this.call<T>('POST', path, data ?? {}); }
  put<T>(path: string, data?: unknown) { return this.call<T>('PUT', path, data); }
  delete<T>(path: string) { return this.call<T>('DELETE', path); }

  /** A plain text post in `community`, returning its id and title. */
  async createPost(community: string, title: string, body = '<p>Created by the end-to-end suite.</p>') {
    const p = await this.post<{ id: number; title: string }>('/posts', { communitySlug: community, type: 'TEXT', title, bodyHtml: body });
    return { id: p.id, title: p.title, path: `/c/${community}/p/${p.id}` };
  }
}

/** Full navigation that waits for the boot-time refresh, so its rotated cookie is stored before anything else. */
export async function gotoAuthed(page: Page, path: string) {
  const refreshed = page.waitForResponse((r) => r.url().includes('/api/v1/auth/refresh'));
  await page.goto(path);
  const res = await refreshed;
  expect(res.status(), 'session refresh').toBe(200);
}

type WorkerFixtures = { sessions: Sessions };

export const test = base.extend<object, WorkerFixtures>({
  sessions: [
    async ({ browser }, use, workerInfo) => {
      const baseURL = (workerInfo.project.use.baseURL as string | undefined) ?? 'http://localhost:5174';
      const s = new Sessions(browser, baseURL);
      await use(s);
      await s.dispose();
    },
    { scope: 'worker' },
  ],
});

export { expect };
