/**
 * Turns the server's OpenAPI description (GET /v3/api-docs, generated from the controllers) into the sections of the
 * Howlers API docs page. Pure functions only, so the page always matches the running server and the logic is tested.
 */

export interface OaSchema {
  $ref?: string;
  type?: string;
  format?: string;
  items?: OaSchema;
  properties?: Record<string, OaSchema>;
  required?: string[];
  enum?: unknown[];
  pattern?: string;
  minLength?: number;
  maxLength?: number;
  maxItems?: number;
  minimum?: number;
  maximum?: number;
  default?: unknown;
  additionalProperties?: boolean | OaSchema;
  oneOf?: OaSchema[];
  allOf?: OaSchema[];
}

export interface OaParameter {
  name: string;
  in: 'path' | 'query' | 'header' | 'cookie';
  required?: boolean;
  schema?: OaSchema;
}

interface OaMedia {
  schema?: OaSchema;
}

export interface OaOperation {
  tags?: string[];
  operationId?: string;
  summary?: string;
  description?: string;
  parameters?: OaParameter[];
  requestBody?: { required?: boolean; content?: Record<string, OaMedia> };
  responses?: Record<string, { description?: string; content?: Record<string, OaMedia> }>;
}

export interface OpenApiDoc {
  info?: { title?: string; version?: string };
  paths: Record<string, Partial<Record<string, OaOperation>>>;
  components?: { schemas?: Record<string, OaSchema> };
}

export type HttpMethod = 'get' | 'post' | 'put' | 'patch' | 'delete';
const METHODS: HttpMethod[] = ['get', 'post', 'put', 'patch', 'delete'];

export type Access = 'required' | 'optional' | 'public' | 'admin';

export interface Endpoint {
  id: string;
  method: HttpMethod;
  path: string;
  title: string;
  group: string;
  access: Access;
  op: OaOperation;
}

export interface Group {
  name: string;
  slug: string;
  blurb: string;
  endpoints: Endpoint[];
}

/** Areas switched off in the app (their routes still exist on the server but aren't part of the product). */
export const HIDDEN_GROUPS = new Set([
  'Podcasts', 'Files', 'Smart Collections', 'Moderator funding', 'Moderator payouts', 'Community AI',
  'Context Mode & Digest', 'Push', 'SEO', 'Meta',
]);

/** Display order and one-line descriptions. Groups the server adds later still show, after these. */
export const GROUP_INFO: { name: string; blurb: string }[] = [
  { name: 'Auth', blurb: 'Sign up, log in, refresh the session, verify email and reset passwords.' },
  { name: 'Account security', blurb: 'Two-factor codes, passkeys, sessions and recovery.' },
  { name: 'Users', blurb: 'Profiles, your own account and settings, following people.' },
  { name: 'Onboarding', blurb: 'Pick topics and communities when you join.' },
  { name: 'Topics', blurb: 'The topics communities belong to.' },
  { name: 'Communities', blurb: 'Browse, create, join and manage communities, rules and flairs.' },
  { name: 'Feeds', blurb: 'Home, Popular, Latest, Rising and Top feeds, and a community’s posts.' },
  { name: 'Posts', blurb: 'Create, read, edit and delete posts; save, hide and vote in polls.' },
  { name: 'Comments', blurb: 'Threaded comments and replies.' },
  { name: 'Votes', blurb: 'Upvote and downvote posts and comments.' },
  { name: 'Reactions', blurb: 'Emoji reactions on posts and comments.' },
  { name: 'Media', blurb: 'Upload images for posts, avatars and chats.' },
  { name: 'Search', blurb: 'Search posts, communities and people.' },
  { name: 'Notifications', blurb: 'Your inbox of replies, mentions and updates.' },
  { name: 'Messages', blurb: 'Direct chats and pack rooms: messages, voice notes, replies, themes.' },
  { name: 'Community Pulse', blurb: 'Moderator-run polls, predictions and debates.' },
  { name: 'Reputation', blurb: 'Topic reputation and the Topics leaderboard.' },
  { name: 'Moderator recognition', blurb: 'The Members and Moderators leaderboards and endorsements.' },
  { name: 'Moderation', blurb: 'Reports, the mod queue, removals, bans, appeals, automod and the mod log.' },
  { name: 'Mutes', blurb: 'Mute people and communities.' },
  { name: 'Admin', blurb: 'Site-wide tools for the super admin.' },
];

export const slugify = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');

const GENERIC: Record<string, string> = {
  get: 'Get', one: 'Get', detail: 'Get', list: 'List', all: 'List', page: 'List', create: 'Create', update: 'Update',
  delete: 'Delete', remove: 'Delete', set: 'Set', upload: 'Upload',
};

const words = (id: string) =>
  id.replace(/_\d+$/, '').replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ').trim().toLowerCase();

export const singular = (n: string) => (/ies$/.test(n) ? `${n.slice(0, -3)}y` : /(ss|us)$/.test(n) ? n : n.replace(/s$/, ''));
const cap = (s: string) => (s ? s[0].toUpperCase() + s.slice(1) : s);

/**
 * A short label for an endpoint: "muteUser" → "Mute user", "block" → "Block"; generic ids use the method's verb and
 * the resource from the path ("create" on /posts → "Create post", "list" on /posts/{id}/comments → "List comments").
 */
export function titleFor(method: HttpMethod, path: string, operationId?: string): string {
  const w = operationId ? words(operationId) : '';
  const statics = path.replace(/^\/api\/v1\/?/, '').split('/').filter((p) => p && !p.startsWith('{'));
  const last = (statics.at(-1) ?? 'resource').replace(/-/g, ' ');
  if (w && !GENERIC[w]) return cap(w);
  const verb = GENERIC[w] ?? { get: 'Get', post: 'Create', put: 'Set', patch: 'Update', delete: 'Delete' }[method];
  return `${verb} ${verb === 'List' ? last : singular(last)}`;
}

const PUBLIC_WRITES = [/^\/api\/v1\/auth\//];

/** Who can call it, from the path and method (the server enforces it; this is for readers). */
export function accessFor(method: HttpMethod, path: string): Access {
  if (/^\/api\/v1\/admin(\/|$)/.test(path)) return 'admin';
  if (PUBLIC_WRITES.some((r) => r.test(path))) return 'public';
  if (/\/(me|mod)(\/|$)/.test(path) || method !== 'get') return 'required';
  return 'optional';
}

/** Every visible endpoint, grouped and ordered for the page. */
export function groupEndpoints(doc: OpenApiDoc): Group[] {
  const byGroup = new Map<string, Endpoint[]>();
  for (const [path, ops] of Object.entries(doc.paths ?? {})) {
    for (const method of METHODS) {
      const op = ops?.[method];
      if (!op) continue;
      const group = op.tags?.[0] ?? 'Other';
      if (HIDDEN_GROUPS.has(group)) continue;
      const list = byGroup.get(group) ?? [];
      list.push({
        id: `${method}-${slugify(path)}`,
        method,
        path,
        title: op.summary || titleFor(method, path, op.operationId),
        group,
        access: accessFor(method, path),
        op,
      });
      byGroup.set(group, list);
    }
  }
  const order = (m: HttpMethod) => METHODS.indexOf(m);
  const known = GROUP_INFO.map((g) => g.name);
  const names = [...byGroup.keys()].sort((a, b) => {
    const ia = known.indexOf(a), ib = known.indexOf(b);
    return (ia < 0 ? 999 : ia) - (ib < 0 ? 999 : ib) || a.localeCompare(b);
  });
  return names.map((name) => ({
    name,
    slug: slugify(name),
    blurb: GROUP_INFO.find((g) => g.name === name)?.blurb ?? '',
    endpoints: (byGroup.get(name) ?? []).sort((a, b) => a.path.localeCompare(b.path) || order(a.method) - order(b.method)),
  }));
}

/** Case-insensitive match on path, title, method or group; every word must match. */
export function filterGroups(groups: Group[], query: string): Group[] {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!terms.length) return groups;
  return groups
    .map((g) => ({
      ...g,
      endpoints: g.endpoints.filter((e) => {
        const hay = `${e.method} ${e.path} ${e.title} ${g.name}`.toLowerCase();
        return terms.every((t) => hay.includes(t));
      }),
    }))
    .filter((g) => g.endpoints.length > 0);
}

export const refName = (ref?: string) => (ref ? ref.split('/').at(-1) ?? ref : '');

export function resolve(doc: OpenApiDoc, s?: OaSchema): OaSchema | undefined {
  if (!s) return undefined;
  if (s.$ref) return doc.components?.schemas?.[refName(s.$ref)];
  return s;
}

/** A short type label: "string", "integer", "PostSummary", "string[]", "TEXT | LINK | IMAGE | POLL". */
export function typeLabel(s?: OaSchema): string {
  if (!s) return 'object';
  if (s.$ref) return refName(s.$ref);
  if (s.enum?.length) return s.enum.map(String).join(' | ');
  if (s.type === 'array') return `${typeLabel(s.items)}[]`;
  if (s.type === 'string' && s.pattern && /^[A-Z_]+(\|[A-Z_]+)+$/.test(s.pattern)) return s.pattern.split('|').join(' | ');
  if (s.format === 'date-time') return 'timestamp';
  if (s.format === 'binary') return 'file';
  return s.type ?? 'object';
}

/** Limits worth showing next to a field ("max 300 characters", "up to 5"). */
export function limitsOf(s?: OaSchema): string {
  if (!s) return '';
  const out: string[] = [];
  if (s.type === 'string') {
    if (s.minLength && s.minLength > 0 && s.maxLength) out.push(`${s.minLength}–${s.maxLength} characters`);
    else if (s.maxLength) out.push(`max ${s.maxLength} characters`);
  }
  if (s.type === 'array' && s.maxItems) out.push(`up to ${s.maxItems}`);
  if (s.minimum != null && s.maximum != null) out.push(`${s.minimum}–${s.maximum}`);
  if (s.default != null) out.push(`default ${String(s.default)}`);
  return out.join(', ');
}

export interface Field {
  name: string;
  type: string;
  required: boolean;
  limits: string;
}

/** The fields of an object schema (one level; nested objects show their type name). */
export function fieldsOf(doc: OpenApiDoc, s?: OaSchema): Field[] {
  const r = resolve(doc, s);
  if (!r?.properties) return [];
  const req = new Set(r.required ?? []);
  return Object.entries(r.properties).map(([name, p]) => ({ name, type: typeLabel(p), required: req.has(name), limits: limitsOf(p) }));
}

export function bodySchema(op: OaOperation): { schema?: OaSchema; contentType?: string } {
  const content = op.requestBody?.content;
  if (!content) return {};
  const contentType = Object.keys(content)[0];
  return { schema: content[contentType]?.schema, contentType };
}

export function responseRows(op: OaOperation): { code: string; text: string; type: string }[] {
  return Object.entries(op.responses ?? {}).map(([code, r]) => {
    const media = r.content ? Object.values(r.content)[0] : undefined;
    return { code, text: r.description ?? '', type: media?.schema ? typeLabel(media.schema) : '' };
  });
}

/** A ready-to-paste curl command for the endpoint (example values for path parameters). */
export function curlFor(e: Endpoint, origin: string, doc: OpenApiDoc): string {
  const path = e.path.replace(/\{(\w+)\}/g, (_, n: string) => (/id$/i.test(n) ? '1' : `<${n}>`));
  const lines = [`curl -X ${e.method.toUpperCase()} "${origin}${path}"`];
  if (e.access !== 'public') lines.push(`  -H "Authorization: Bearer $TOKEN"`);
  const { schema, contentType } = bodySchema(e.op);
  if (contentType?.includes('multipart')) {
    lines.push('  -F "file=@picture.png"');
  } else if (schema) {
    const example: Record<string, unknown> = {};
    for (const f of fieldsOf(doc, schema).filter((x) => x.required)) {
      example[f.name] = f.type === 'integer' || f.type === 'number' ? 1 : f.type === 'boolean' ? true : f.type.endsWith('[]') ? [] : '…';
    }
    lines.push('  -H "Content-Type: application/json"');
    lines.push(`  -d '${JSON.stringify(example)}'`);
  }
  return lines.join(' \\\n');
}
