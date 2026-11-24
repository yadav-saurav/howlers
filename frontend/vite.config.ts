/// <reference types="vitest" />
import { configDefaults, defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';
import { serviceWorkerPlugin } from './pwa/serviceWorkerPlugin';

const backend = process.env.VITE_BACKEND_URL ?? 'http://localhost:8080';

/** robots.txt and sitemaps come from the backend (D-039), as nginx does in Docker. */
const CRAWLER_DOCS = String.raw`^/(robots\.txt|sitemap\.xml|sitemaps/.*)$`;
const crawlerDocs = { target: backend, changeOrigin: true, rewrite: (p: string) => `/api/v1/seo${p}` };

/**
 * Dev/E2E only: when this dev server runs on a port the backend's CORS_ALLOWED_ORIGINS doesn't list (e.g. a second
 * server on 5174 for Playwright against a backend configured for 5173), set HOWLERS_DEV_PROXY_ORIGIN to an allowed
 * origin. The proxy then rewrites the Origin header, but only for requests whose Origin is this dev server's own
 * localhost origin, so a foreign site can never borrow it. Unset (the default) means headers pass through unchanged.
 */
const proxyOrigin = process.env.HOWLERS_DEV_PROXY_ORIGIN;

type ProxyLike = {
  on(event: 'proxyReq' | 'proxyReqWs', cb: (proxyReq: { getHeader(n: string): unknown; setHeader(n: string, v: string): void }, req: { headers: Record<string, string | string[] | undefined> }) => void): void;
};

function rewriteOwnOrigin(proxy: ProxyLike) {
  if (!proxyOrigin) return;
  const rewrite = (
    proxyReq: { setHeader(n: string, v: string): void },
    req: { headers: Record<string, string | string[] | undefined> },
  ) => {
    const origin = req.headers.origin;
    const host = req.headers.host;
    if (typeof origin === 'string' && typeof host === 'string' && /^(localhost|127\.0\.0\.1)(:\d+)?$/.test(host)
      && origin === `http://${host}`) {
      proxyReq.setHeader('origin', proxyOrigin);
    }
  };
  proxy.on('proxyReq', rewrite);
  proxy.on('proxyReqWs', rewrite);
}

export default defineConfig({
  plugins: [react(), serviceWorkerPlugin()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: true, configure: (p) => rewriteOwnOrigin(p as unknown as ProxyLike) },
      '/swagger-ui': { target: backend, changeOrigin: true },
      [CRAWLER_DOCS]: crawlerDocs,
      '/ws': {
        target: backend.replace(/^http/, 'ws'), ws: true, changeOrigin: true,
        configure: (p) => rewriteOwnOrigin(p as unknown as ProxyLike),
      },
    },
  },
  preview: {
    proxy: {
      [CRAWLER_DOCS]: crawlerDocs,
      '/api': { target: backend, changeOrigin: true, configure: (p) => rewriteOwnOrigin(p as unknown as ProxyLike) },
      '/ws': {
        target: backend.replace(/^http/, 'ws'), ws: true, changeOrigin: true,
        configure: (p) => rewriteOwnOrigin(p as unknown as ProxyLike),
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          editor: ['@tiptap/react', '@tiptap/starter-kit', '@tiptap/extension-link', '@tiptap/extension-placeholder'],
          charts: ['recharts'],
          markdown: ['react-markdown', 'remark-gfm'],
        },
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // Playwright specs live in e2e/ and run with `npm run e2e`, not Vitest.
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
});
