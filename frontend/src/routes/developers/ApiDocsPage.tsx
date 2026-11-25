import { useQuery } from '@tanstack/react-query';
import { Check, Copy, KeyRound, Lock, Search, ShieldHalf, Unlock } from 'lucide-react';
import { useMemo, useState, type ReactNode } from 'react';
import { Skeleton } from '@/components/ui/Display';
import { ErrorState } from '@/components/ui/States';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import {
  bodySchema, curlFor, fieldsOf, filterGroups, groupEndpoints, responseRows, typeLabel,
  type Access, type Endpoint, type Group, type HttpMethod, type OpenApiDoc,
} from '@/features/apidocs/openapi';
import { cn } from '@/utils/format';

async function loadSpec(): Promise<OpenApiDoc> {
  const res = await fetch('/v3/api-docs', { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`The API description could not be loaded (${res.status}).`);
  return res.json();
}

const METHOD_STYLE: Record<HttpMethod, string> = {
  get: 'bg-[#1f9d74]/15 text-[#1f9d74] dark:text-[#5fe0b4]',
  post: 'bg-accent/15 text-accent',
  put: 'bg-[#c47a12]/15 text-[#a8650b] dark:text-[#ffbf5e]',
  patch: 'bg-[#c47a12]/15 text-[#a8650b] dark:text-[#ffbf5e]',
  delete: 'bg-danger/15 text-danger',
};

const ACCESS: Record<Access, { label: string; icon: ReactNode; cls: string }> = {
  public: { label: 'Public', icon: <Unlock size={12} />, cls: 'text-muted' },
  optional: { label: 'Sign-in optional', icon: <Unlock size={12} />, cls: 'text-muted' },
  required: { label: 'Sign-in required', icon: <Lock size={12} />, cls: 'text-accent' },
  admin: { label: 'Super admin only', icon: <ShieldHalf size={12} />, cls: 'text-danger' },
};

function MethodBadge({ method }: { method: HttpMethod }) {
  return (
    <span className={cn('inline-flex w-[58px] shrink-0 justify-center rounded-md px-1.5 py-0.5 font-mono text-[11px] font-bold uppercase', METHOD_STYLE[method])}>
      {method}
    </span>
  );
}

/** The path with its {parameters} highlighted. */
function PathText({ path }: { path: string }) {
  const parts = path.replace(/^\/api\/v1/, '').split(/(\{[^}]+\})/g);
  return (
    <code className="min-w-0 break-all font-mono text-[13px]">
      <span className="text-faint">/api/v1</span>
      {parts.map((p, i) => (p.startsWith('{') ? <span key={i} className="text-accent">{p}</span> : <span key={i}>{p}</span>))}
    </code>
  );
}

function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      onClick={() => {
        void navigator.clipboard?.writeText(text).then(() => {
          setDone(true);
          window.setTimeout(() => setDone(false), 1500);
        });
      }}
      className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted hover:bg-surface-3 hover:text-text"
    >
      {done ? <Check size={13} /> : <Copy size={13} />} {done ? 'Copied' : label}
    </button>
  );
}

function Code({ children, copy }: { children: string; copy?: boolean }) {
  return (
    <div className="relative overflow-hidden rounded-xl border border-border bg-surface-2/70">
      {copy && <div className="absolute right-1.5 top-1.5"><CopyButton text={children} /></div>}
      <pre className="overflow-x-auto p-3 pr-20 font-mono text-[12.5px] leading-relaxed text-text"><code>{children}</code></pre>
    </div>
  );
}

function Table({ head, rows }: { head: string[]; rows: ReactNode[][] }) {
  return (
    <div className="overflow-x-auto rounded-xl border border-border">
      <table className="w-full text-left text-[13px]">
        <thead className="bg-surface-2/60 text-xs text-muted">
          <tr>{head.map((h) => <th key={h} className="px-3 py-2 font-medium">{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-border">
          {rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j} className="px-3 py-2 align-top">{c}</td>)}</tr>)}
        </tbody>
      </table>
    </div>
  );
}

function EndpointCard({ e, doc, origin }: { e: Endpoint; doc: OpenApiDoc; origin: string }) {
  const params = e.op.parameters ?? [];
  const { schema, contentType } = bodySchema(e.op);
  const fields = schema ? fieldsOf(doc, schema) : [];
  const responses = responseRows(e.op);
  const access = ACCESS[e.access];
  return (
    <details id={e.id} className="group scroll-mt-24 rounded-xl border border-border bg-surface open:shadow-sm">
      <summary className="flex cursor-pointer list-none flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2.5 hover:bg-surface-2/50 [&::-webkit-details-marker]:hidden">
        <MethodBadge method={e.method} />
        <PathText path={e.path} />
        <span className="ml-auto flex items-center gap-3 text-xs">
          <span className="text-muted">{e.title}</span>
          <span className={cn('hidden items-center gap-1 sm:inline-flex', access.cls)}>{access.icon}{access.label}</span>
        </span>
      </summary>
      <div className="space-y-4 border-t border-border px-3 py-4">
        <p className={cn('inline-flex items-center gap-1 text-xs sm:hidden', access.cls)}>{access.icon}{access.label}</p>
        {params.length > 0 && (
          <section className="space-y-2">
            <h4 className="text-xs font-semibold uppercase tracking-wide text-muted">Parameters</h4>
            <Table
              head={['Name', 'In', 'Type', '']}
              rows={params.map((p) => [
                <code key="n" className="font-mono">{p.name}</code>,
                <span key="i" className="text-muted">{p.in}</span>,
                <span key="t" className="font-mono text-muted">{typeLabel(p.schema)}{p.schema?.default != null ? ` = ${String(p.schema.default)}` : ''}</span>,
                p.required ? <span key="r" className="text-xs font-medium text-accent">required</span> : <span key="r" className="text-xs text-faint">optional</span>,
              ])}
            />
          </section>
        )}
        {schema && (
          <section className="space-y-2">
            <h4 className="text-xs font-semibold uppercase tracking-wide text-muted">
              Request body <span className="font-mono normal-case text-faint">{contentType} · {typeLabel(schema)}</span>
            </h4>
            {fields.length > 0 ? (
              <Table
                head={['Field', 'Type', '', 'Limits']}
                rows={fields.map((f) => [
                  <code key="n" className="font-mono">{f.name}</code>,
                  <span key="t" className="font-mono text-muted">{f.type}</span>,
                  f.required ? <span key="r" className="text-xs font-medium text-accent">required</span> : <span key="r" className="text-xs text-faint">optional</span>,
                  <span key="l" className="text-xs text-muted">{f.limits}</span>,
                ])}
              />
            ) : (
              <p className="text-sm text-muted">{contentType?.includes('multipart') ? 'A file upload in the field “file”.' : 'See the schema name above.'}</p>
            )}
          </section>
        )}
        {responses.length > 0 && (
          <section className="space-y-2">
            <h4 className="text-xs font-semibold uppercase tracking-wide text-muted">Responses</h4>
            <ul className="space-y-1 text-[13px]">
              {responses.map((r) => (
                <li key={r.code} className="flex flex-wrap gap-2">
                  <span className={cn('rounded px-1.5 font-mono text-xs font-bold', r.code.startsWith('2') ? 'bg-[#1f9d74]/15 text-[#1f9d74] dark:text-[#5fe0b4]' : 'bg-danger/15 text-danger')}>{r.code}</span>
                  <span className="text-muted">{r.text}</span>
                  {r.type && <code className="font-mono text-text">{r.type}</code>}
                </li>
              ))}
              {e.access !== 'public' && <li className="text-xs text-faint">Errors use the shared error body (see Errors).</li>}
            </ul>
          </section>
        )}
        <section className="space-y-2">
          <h4 className="text-xs font-semibold uppercase tracking-wide text-muted">Try it</h4>
          <Code copy>{curlFor(e, origin, doc)}</Code>
        </section>
      </div>
    </details>
  );
}

const GUIDES = [
  { id: 'start', label: 'Getting started' },
  { id: 'auth', label: 'Authentication' },
  { id: 'errors', label: 'Errors' },
  { id: 'pagination', label: 'Pagination' },
  { id: 'realtime', label: 'Real-time' },
];

function Guide({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} className="scroll-mt-24 space-y-3">
      <h2 className="font-serif text-xl font-semibold">{title}</h2>
      {children}
    </section>
  );
}

function Guides({ origin }: { origin: string }) {
  const api = `${origin}/api/v1`;
  return (
    <div className="space-y-10">
      <Guide id="start" title="Getting started">
        <p className="text-sm leading-relaxed text-muted">
          Everything you can do in Howlers goes through this JSON API. Send and receive JSON, use ISO-8601 UTC
          timestamps, and expect numeric IDs. Successful responses return the object directly, with no envelope.
        </p>
        <div className="flex flex-wrap items-center gap-2 rounded-xl border border-border bg-surface-2/60 px-3 py-2 text-sm">
          <span className="text-muted">Base URL</span>
          <code className="font-mono">{api}</code>
          <span className="ml-auto"><CopyButton text={api} /></span>
        </div>
      </Guide>
      <Guide id="auth" title="Authentication">
        <p className="text-sm leading-relaxed text-muted">
          Log in to get a short-lived access token (15 minutes), then send it as a Bearer token. The refresh token is kept in
          a secure, HttpOnly cookie; call <code className="font-mono">POST /auth/refresh</code> to get a new access token
          when the old one expires.
        </p>
        <Code copy>{`curl -X POST "${api}/auth/login" \\
  -H "Content-Type: application/json" \\
  -d '{"login":"your_username","password":"your-password"}'

# → { "accessToken": "eyJ…", "user": { … } }
export TOKEN="eyJ…"

curl "${api}/users/me" -H "Authorization: Bearer $TOKEN"`}</Code>
        <ul className="grid gap-2 text-sm sm:grid-cols-2">
          {(['public', 'optional', 'required', 'admin'] as Access[]).map((a) => (
            <li key={a} className={cn('flex items-center gap-2 rounded-lg bg-surface-2/50 px-3 py-2', ACCESS[a].cls)}>
              {ACCESS[a].icon}
              <span className="font-medium">{ACCESS[a].label}</span>
              <span className="text-xs text-muted">
                {a === 'public' ? 'no token needed' : a === 'optional' ? 'works signed out, more with a token' : a === 'required' ? 'Bearer token' : 'Bearer token of the super admin'}
              </span>
            </li>
          ))}
        </ul>
      </Guide>
      <Guide id="errors" title="Errors">
        <p className="text-sm leading-relaxed text-muted">
          Every 4xx and 5xx response has the same body. <code className="font-mono">fieldErrors</code> says which form field
          is wrong, and <code className="font-mono">traceId</code> (also in the <code className="font-mono">X-Trace-Id</code>{' '}
          header) helps find the request in the logs. Too many requests return <code className="font-mono">429 RATE_LIMITED</code>.
        </p>
        <Code>{`{
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "Title is required",
  "fieldErrors": { "title": "Title is required" },
  "traceId": "c0ffee…",
  "timestamp": "2026-11-25T10:00:00Z"
}`}</Code>
        <p className="text-xs text-faint">Codes: VALIDATION_FAILED, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, RATE_LIMITED, BAD_REQUEST, INTERNAL_ERROR.</p>
      </Guide>
      <Guide id="pagination" title="Pagination">
        <p className="text-sm leading-relaxed text-muted">
          Feeds, comments, notifications and search use cursors: pass <code className="font-mono">cursor</code> from the
          previous page until <code className="font-mono">nextCursor</code> is null. Directories and admin lists use pages.
        </p>
        <div className="grid gap-3 md:grid-cols-2">
          <Code>{`GET /feed?tab=popular&limit=25
→ { "items": [ … ], "nextCursor": "eyJ…" }

GET /feed?tab=popular&cursor=eyJ…`}</Code>
          <Code>{`GET /communities?page=0&size=20
→ { "items": [ … ], "page": 0, "size": 20,
    "totalElements": 120, "totalPages": 6 }`}</Code>
        </div>
      </Guide>
      <Guide id="realtime" title="Real-time">
        <p className="text-sm leading-relaxed text-muted">
          Live updates (new messages, typing, read receipts, notifications, new posts and comments) arrive over a
          WebSocket at <code className="font-mono">{origin.replace(/^http/, 'ws')}/ws</code> using STOMP 1.2. Send the access
          token in the CONNECT frame and subscribe to <code className="font-mono">/user/queue/events</code>. Each event is{' '}
          <code className="font-mono">{'{ type, payload }'}</code>; the REST API stays the source of truth, so refetch what an
          event says changed.
        </p>
        <Code>{`CONNECT
Authorization:Bearer eyJ…

SUBSCRIBE
destination:/user/queue/events

→ { "type": "MESSAGE", "payload": { … } }`}</Code>
      </Guide>
    </div>
  );
}

function Sidebar({ groups, total }: { groups: Group[]; total: number }) {
  const link = 'block truncate rounded-md px-2 py-1 text-sm text-muted hover:bg-surface-2 hover:text-text';
  return (
    <nav aria-label="API sections" className="space-y-5">
      <div>
        <p className="mb-1 px-2 text-[11px] font-semibold uppercase tracking-wider text-faint">Guides</p>
        {GUIDES.map((g) => <a key={g.id} href={`#${g.id}`} className={link}>{g.label}</a>)}
      </div>
      <div>
        <p className="mb-1 px-2 text-[11px] font-semibold uppercase tracking-wider text-faint">Reference · {total} endpoints</p>
        {groups.map((g) => (
          <a key={g.slug} href={`#${g.slug}`} className={cn(link, 'flex items-center justify-between gap-2')}>
            <span className="truncate">{g.name}</span>
            <span className="text-[11px] tabular-nums text-faint">{g.endpoints.length}</span>
          </a>
        ))}
      </div>
    </nav>
  );
}

/**
 * Howlers API documentation: guides plus a full endpoint reference read live from the server's own description
 * (GET /v3/api-docs), so it always matches what is deployed. Areas switched off in the app are left out.
 */
export default function ApiDocsPage() {
  useDocumentTitle('API docs');
  const origin = typeof window === 'undefined' ? '' : window.location.origin;
  const spec = useQuery({ queryKey: ['api-docs'], queryFn: loadSpec, staleTime: 10 * 60_000 });
  const [query, setQuery] = useState('');
  const groups = useMemo(() => (spec.data ? groupEndpoints(spec.data) : []), [spec.data]);
  const shown = useMemo(() => filterGroups(groups, query), [groups, query]);
  const total = groups.reduce((n, g) => n + g.endpoints.length, 0);
  const found = shown.reduce((n, g) => n + g.endpoints.length, 0);

  return (
    <div className="mx-auto w-full max-w-6xl px-4 py-10 sm:py-14">
      <header className="mb-10 max-w-3xl">
        <p className="text-xs font-semibold uppercase tracking-[0.2em] text-accent">Developers</p>
        <h1 className="mt-2 font-serif text-4xl font-semibold tracking-tight">Howlers API</h1>
        <p className="mt-3 text-base leading-relaxed text-muted">
          Build on Howlers: read feeds and communities, post and comment, chat, moderate and follow the leaderboards. This
          reference is generated from the running server, so it is always up to date.
        </p>
        <div className="mt-4 flex flex-wrap gap-2 text-xs">
          <span className="inline-flex items-center gap-1 rounded-full bg-surface-2 px-3 py-1 text-muted"><KeyRound size={13} /> Bearer token auth</span>
          <span className="rounded-full bg-surface-2 px-3 py-1 text-muted">JSON over HTTPS</span>
          <span className="rounded-full bg-surface-2 px-3 py-1 text-muted">Version v1</span>
          {total > 0 && <span className="rounded-full bg-surface-2 px-3 py-1 text-muted">{total} endpoints</span>}
        </div>
      </header>

      <div className="grid gap-10 lg:grid-cols-[220px_minmax(0,1fr)]">
        <aside className="hidden lg:block">
          <div className="sticky top-20 max-h-[calc(100vh-6rem)] overflow-y-auto pb-6">
            {spec.data ? <Sidebar groups={shown} total={total} /> : <Skeleton className="h-96" />}
          </div>
        </aside>

        <main className="min-w-0 space-y-14">
          <Guides origin={origin} />

          <section aria-labelledby="reference-title" className="space-y-6">
            <div className="flex flex-wrap items-end justify-between gap-3">
              <h2 id="reference-title" className="font-serif text-2xl font-semibold">Reference</h2>
              <label className="relative w-full sm:w-80">
                <span className="sr-only">Search endpoints</span>
                <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" />
                <input
                  type="search"
                  value={query}
                  onChange={(ev) => setQuery(ev.target.value)}
                  placeholder="Search endpoints, e.g. “post comments”"
                  className="h-10 w-full rounded-full border border-border bg-surface pl-9 pr-4 text-sm outline-none focus:border-accent"
                />
              </label>
            </div>
            {query && spec.data && <p className="text-sm text-muted" aria-live="polite">{found} {found === 1 ? 'endpoint' : 'endpoints'} match “{query}”.</p>}

            {spec.isPending ? (
              <div className="space-y-3">{Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-11" />)}</div>
            ) : spec.isError ? (
              <ErrorState error={spec.error} onRetry={() => spec.refetch()} />
            ) : (
              shown.map((g) => (
                <section key={g.slug} id={g.slug} className="scroll-mt-24 space-y-3" aria-labelledby={`${g.slug}-title`}>
                  <div>
                    <h3 id={`${g.slug}-title`} className="text-lg font-semibold">{g.name}</h3>
                    {g.blurb && <p className="text-sm text-muted">{g.blurb}</p>}
                  </div>
                  <div className="space-y-2">
                    {g.endpoints.map((e) => <EndpointCard key={e.id} e={e} doc={spec.data!} origin={origin} />)}
                  </div>
                </section>
              ))
            )}
          </section>
        </main>
      </div>
    </div>
  );
}
