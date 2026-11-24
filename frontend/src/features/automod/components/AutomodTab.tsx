import { Bot, FlaskConical, Pencil, Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Badge, Skeleton } from '@/components/ui/Display';
import { Field, Input, Select, Textarea } from '@/components/ui/Field';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { Switch } from '@/components/ui/Switch';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { AutomodAction, AutomodKind, AutomodPreview, AutomodRule, AutomodRuleInput, AutomodScope } from '@/types/automod';
import { cn, timeAgo } from '@/utils/format';
import { automodApi, useAutomodHits, useAutomodMutation, useAutomodRules } from '../api';
import { ACTION_INFO, KIND_INFO, SCOPE_LABEL, describeRule, emptyRule, toRequest, validateRule } from '../helpers';

const card = 'rounded-2xl border border-border';

function PreviewResult({ slug, p }: { slug: string; p: AutomodPreview }) {
  return (
    <div className="space-y-2 rounded-xl bg-surface-2/60 p-3" role="status">
      <p className="text-sm font-medium">
        {p.matchCount === 0 ? 'No matches' : `${p.matchCount} ${p.matchCount === 1 ? 'match' : 'matches'}`} in the latest {p.scannedPosts} posts and {p.scannedComments} comments.
        {p.timedOut > 0 && ` ${p.timedOut} items took too long to check and were skipped.`}
      </p>
      <p className="text-xs text-muted">Nothing was changed. Matches by moderators are shown but would be exempt.</p>
      {p.matches.length > 0 && (
        <ul className="divide-y divide-border text-sm">
          {p.matches.map((m) => (
            <li key={`${m.targetType}-${m.commentId ?? m.postId}`} className="py-2">
              <div className="flex flex-wrap items-center gap-x-2 text-xs text-muted">
                <span className="font-semibold">{m.targetType === 'POST' ? 'Post' : 'Comment'}</span>
                {m.author && <span>u/{m.author.username}</span>}
                <span>{timeAgo(m.createdAt)}</span>
                <span>· {m.detail}</span>
                {m.authorExempt && <Badge tone="info" className="rounded-full px-2">exempt</Badge>}
              </div>
              <Link to={`/c/${slug}/p/${m.postId}${m.commentId ? `#c-${m.commentId}` : ''}`} className="mt-0.5 line-clamp-2 block hover:underline">{m.excerpt}</Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Create or edit a rule, with "Test against recent content" before saving. */
function RuleEditor({ slug, initial, ruleId, onDone }: { slug: string; initial: AutomodRuleInput; ruleId?: number; onDone: () => void }) {
  const [r, setR] = useState<AutomodRuleInput>(initial);
  const [touched, setTouched] = useState(false);
  const [preview, setPreview] = useState<AutomodPreview | null>(null);
  const [serverError, setServerError] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);
  const save = useAutomodMutation(slug, (body: AutomodRuleInput) =>
    ruleId == null ? automodApi.create(slug, body) : automodApi.update(slug, ruleId, body));
  const errors = touched ? validateRule(r) : {};
  const info = KIND_INFO[r.kind];
  const set = <K extends keyof AutomodRuleInput>(k: K, v: AutomodRuleInput[K]) => {
    setR((x) => ({ ...x, [k]: v }));
    setPreview(null);
  };
  const valid = () => {
    setTouched(true);
    return Object.keys(validateRule(r)).length === 0;
  };

  const test = async () => {
    if (!valid()) return;
    setServerError(null);
    setTesting(true);
    try {
      setPreview(await automodApi.preview(slug, toRequest(r)));
    } catch (e) {
      setServerError(errorMessage(e));
    } finally {
      setTesting(false);
    }
  };
  const submit = () => {
    if (!valid()) return;
    setServerError(null);
    save.mutate(toRequest(r), {
      onSuccess: () => {
        toast.success(ruleId == null ? 'Rule created' : 'Rule saved');
        onDone();
      },
      onError: (e) => setServerError(errorMessage(e)),
    });
  };

  return (
    <form className={cn(card, 'space-y-4 p-4')} onSubmit={(e) => { e.preventDefault(); submit(); }} aria-label={ruleId == null ? 'New automod rule' : 'Edit automod rule'}>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Rule name" error={errors.name}>
          {(p) => <Input {...p} value={r.name} maxLength={80} onChange={(e) => set('name', e.target.value)} placeholder="e.g. No sales posts" />}
        </Field>
        <Field label="Type">
          {(p) => (
            <Select {...p} value={r.kind} onChange={(e) => {
              const kind = e.target.value as AutomodKind;
              setR((x) => ({ ...emptyRule(kind), name: x.name, reason: x.reason, action: x.action, appliesTo: x.appliesTo, enabled: x.enabled }));
              setPreview(null);
            }}>
              {(Object.keys(KIND_INFO) as AutomodKind[]).map((k) => <option key={k} value={k}>{KIND_INFO[k].label}</option>)}
            </Select>
          )}
        </Field>
      </div>
      {info.uses === 'pattern' ? (
        <Field label={info.label} error={errors.pattern} hint={info.help}>
          {(p) => (
            <Textarea {...p} value={r.pattern ?? ''} rows={r.kind === 'REGEX' ? 2 : 4} maxLength={2000} spellCheck={false}
              className="font-mono text-[13px]" onChange={(e) => set('pattern', e.target.value)} />
          )}
        </Field>
      ) : (
        <Field label={info.thresholdLabel ?? 'Threshold'} error={errors.threshold} hint={info.help}>
          {(p) => (
            <Input {...p} type="number" inputMode="numeric" className="max-w-40" value={r.threshold ?? ''}
              onChange={(e) => set('threshold', e.target.value === '' ? null : Number(e.target.value))} />
          )}
        </Field>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Action" hint={ACTION_INFO[r.action].help}>
          {(p) => (
            <Select {...p} value={r.action} onChange={(e) => set('action', e.target.value as AutomodAction)}>
              {(Object.keys(ACTION_INFO) as AutomodAction[]).map((a) => <option key={a} value={a}>{ACTION_INFO[a].label}</option>)}
            </Select>
          )}
        </Field>
        <Field label="Applies to">
          {(p) => (
            <Select {...p} value={r.appliesTo} onChange={(e) => set('appliesTo', e.target.value as AutomodScope)}>
              {(Object.keys(SCOPE_LABEL) as AutomodScope[]).map((s) => <option key={s} value={s}>{SCOPE_LABEL[s]}</option>)}
            </Select>
          )}
        </Field>
      </div>
      <Field label="Reason shown to the author" error={errors.reason} hint="Sent when the rule holds or removes something. Keep it specific and kind.">
        {(p) => <Input {...p} value={r.reason} maxLength={200} onChange={(e) => set('reason', e.target.value)} placeholder="e.g. Sales posts are reviewed by a moderator first" />}
      </Field>
      <div className="max-w-sm">
        <Switch checked={r.enabled} onCheckedChange={(v: boolean) => set('enabled', v)} label="Rule is on"
          description="Off keeps the rule here without applying it." />
      </div>
      {serverError && <p role="alert" className="text-sm text-danger">{serverError}</p>}
      {preview && <PreviewResult slug={slug} p={preview} />}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" size="sm" loading={save.isPending}>{ruleId == null ? 'Create rule' : 'Save rule'}</Button>
        <Button size="sm" variant="secondary" loading={testing} onClick={() => void test()}><FlaskConical size={15} /> Test against recent content</Button>
        <Button size="sm" variant="ghost" onClick={onDone}>Cancel</Button>
      </div>
    </form>
  );
}

function RuleRow({ slug, rule }: { slug: string; rule: AutomodRule }) {
  const [editing, setEditing] = useState(false);
  const toggle = useAutomodMutation(slug, (enabled: boolean) => automodApi.update(slug, rule.id, { enabled }));
  const remove = useAutomodMutation(slug, () => automodApi.remove(slug, rule.id));
  if (editing) return <li className="p-2"><RuleEditor slug={slug} initial={rule} ruleId={rule.id} onDone={() => setEditing(false)} /></li>;
  return (
    <li className="flex flex-wrap items-start gap-3 p-4">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-semibold">{rule.name}</span>
          {!rule.enabled && <Badge className="rounded-full px-2">off</Badge>}
          <Badge tone={rule.action === 'REMOVE' ? 'danger' : rule.action === 'HOLD' ? 'warning' : 'info'} className="rounded-full px-2">
            {ACTION_INFO[rule.action].label}
          </Badge>
        </div>
        <p className="mt-0.5 text-xs text-muted">{describeRule(rule)}</p>
        <p className="mt-0.5 text-xs text-faint">
          {rule.hits30d} {rule.hits30d === 1 ? 'hit' : 'hits'} in 30 days · authors see “{rule.reason}”
        </p>
      </div>
      <div className="flex items-center gap-1">
        <Button size="sm" variant="ghost" loading={toggle.isPending} aria-label={`${rule.enabled ? 'Turn off' : 'Turn on'} ${rule.name}`}
          onClick={() => toggle.mutate(!rule.enabled, { onError: (e) => toast.error(errorMessage(e)) })}>
          {rule.enabled ? 'Turn off' : 'Turn on'}
        </Button>
        <button type="button" aria-label={`Edit ${rule.name}`} onClick={() => setEditing(true)} className="rounded-full p-2 text-muted hover:bg-surface-2 hover:text-text"><Pencil size={15} /></button>
        <button type="button" aria-label={`Delete ${rule.name}`} className="rounded-full p-2 text-muted hover:bg-surface-2 hover:text-danger"
          onClick={() => {
            if (window.confirm(`Delete the rule “${rule.name}”? Content it already acted on stays as it is.`)) {
              remove.mutate(undefined, { onSuccess: () => toast.success('Rule deleted'), onError: (e) => toast.error(errorMessage(e)) });
            }
          }}>
          <Trash2 size={15} />
        </button>
      </div>
    </li>
  );
}

function Hits({ slug }: { slug: string }) {
  const q = useAutomodHits(slug);
  if (q.isPending) return <Skeleton className="h-24 rounded-2xl" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  if (q.data.length === 0) return <p className="text-sm text-muted">No automod hits yet.</p>;
  return (
    <ul className={cn(card, 'divide-y divide-border')}>
      {q.data.map((h) => (
        <li key={h.id} className="p-3 text-sm">
          <div className="flex flex-wrap items-center gap-x-2 text-xs text-muted">
            <span className="font-semibold text-text">{h.ruleName}</span>
            <Badge tone={h.outcome === 'REMOVE' ? 'danger' : h.outcome === 'HOLD' ? 'warning' : 'info'} className="rounded-full px-2">
              {h.outcome === 'REMOVE' ? 'removed' : h.outcome === 'HOLD' ? 'held' : 'flagged'}
            </Badge>
            <span>{h.targetType === 'POST' ? 'post' : 'comment'}</span>
            {h.author && <Link to={`/u/${h.author.username}`} className="hover:underline">u/{h.author.username}</Link>}
            <span>{timeAgo(h.createdAt)}</span>
            <span>· {h.detail}</span>
          </div>
          {h.postId != null ? (
            <Link to={`/c/${slug}/p/${h.postId}${h.commentId ? `#c-${h.commentId}` : ''}`} className="mt-0.5 line-clamp-2 block hover:underline">{h.excerpt}</Link>
          ) : (
            <p className="mt-0.5 line-clamp-2 text-muted">{h.excerpt}</p>
          )}
        </li>
      ))}
    </ul>
  );
}

/** Mod tools → Automod (D-045). Moderators and admins are exempt from every rule; automod never bans. */
export function AutomodTab({ slug }: { slug: string }) {
  const rules = useAutomodRules(slug);
  const [creating, setCreating] = useState(false);
  return (
    <div className="space-y-5">
      <p className="text-xs text-muted">
        Rules run on every new post and comment here, after the site-wide spam checks. When several match, the strongest
        action wins (remove, then hold, then flag). Moderators and admins are exempt, automod never bans anyone, and every
        hit is written to the mod log. Edits to existing posts and comments are not re-checked yet.
      </p>
      {creating ? (
        <RuleEditor slug={slug} initial={emptyRule()} onDone={() => setCreating(false)} />
      ) : (
        <Button size="sm" onClick={() => setCreating(true)}><Plus size={15} /> New rule</Button>
      )}
      {rules.isPending ? (
        <Skeleton className="h-28 rounded-2xl" />
      ) : rules.isError ? (
        <ErrorState error={rules.error} onRetry={() => rules.refetch()} />
      ) : rules.data.length === 0 ? (
        !creating && <EmptyState icon={Bot} title="No automod rules yet" description="Add keyword or pattern filters, a link limit, blocked domains, or a minimum account age or karma." />
      ) : (
        <ul className={cn(card, 'divide-y divide-border')} aria-label="Automod rules">
          {rules.data.map((r) => <RuleRow key={r.id} slug={slug} rule={r} />)}
        </ul>
      )}
      <section aria-labelledby="automod-hits" className="space-y-2">
        <h2 id="automod-hits" className="text-sm font-semibold">Recent hits</h2>
        <Hits slug={slug} />
      </section>
    </div>
  );
}
