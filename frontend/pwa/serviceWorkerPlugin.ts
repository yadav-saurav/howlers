import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { Plugin } from 'vite';

/**
 * Emits /sw.js (D-039): the hand-written worker in pwa/sw.js with a build-specific version and precache list
 * prepended. The precache is the app shell only: the offline page, icons, the manifest and the entry chunks
 * (JS/CSS that index.html loads directly). Lazy route chunks and fonts are cached on first use by the worker.
 *
 * In `vite dev`, /sw.js is served with `dev: true` (no caching at all) so push can be tried locally; the app only
 * registers it in dev when VITE_ENABLE_SW_IN_DEV=true.
 */
const source = () => readFileSync(fileURLToPath(new URL('./sw.js', import.meta.url)), 'utf8');

const SHELL = ['/offline.html', '/favicon.svg', '/manifest.webmanifest', '/icons/icon-192.png', '/icons/badge-72.png'];

export function swCode(config: { version: string; precache: string[]; dev: boolean }): string {
  return `const SW_CONFIG = ${JSON.stringify(config)};\n${source()}`;
}

export function serviceWorkerPlugin(): Plugin {
  return {
    name: 'howlers-service-worker',
    configureServer(server) {
      server.middlewares.use('/sw.js', (_req, res) => {
        res.setHeader('Content-Type', 'text/javascript; charset=utf-8');
        res.setHeader('Cache-Control', 'no-store');
        res.end(swCode({ version: 'dev', precache: [], dev: true }));
      });
    },
    generateBundle(_options, bundle) {
      const entry: string[] = [];
      for (const [fileName, chunk] of Object.entries(bundle)) {
        if (chunk.type === 'chunk' && chunk.isEntry) {
          entry.push(`/${fileName}`);
          for (const imp of chunk.imports) entry.push(`/${imp}`);
          for (const css of chunk.viteMetadata?.importedCss ?? []) entry.push(`/${css}`);
        }
      }
      const precache = [...new Set([...SHELL, ...entry])].sort();
      // Any change to any hashed output file name (or to the worker itself) is a new version.
      const version = createHash('sha256').update(Object.keys(bundle).sort().join('\n')).update(source())
        .update(readFileSync(fileURLToPath(new URL('../public/offline.html', import.meta.url))))
        .update(readFileSync(fileURLToPath(new URL('../public/manifest.webmanifest', import.meta.url))))
        .digest('hex').slice(0, 12);
      this.emitFile({ type: 'asset', fileName: 'sw.js', source: swCode({ version, precache, dev: false }) });
    },
  };
}
