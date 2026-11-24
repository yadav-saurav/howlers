import { CalendarDays, Lock, ShieldCheck } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Avatar, Badge } from '@/components/ui/Display';
import { RailPanel } from '@/layouts/RightRail';
import type { CommunityDetail } from '@/types/api';
import { formatDate } from '@/utils/format';

export function AboutCard({ c }: { c: CommunityDetail }) {
  return (
    <RailPanel title={c.name}>
      <div className="space-y-3 px-4 pb-4 pt-1 text-sm">
        {c.description && <p className="leading-relaxed text-text/85">{c.description}</p>}
        <div className="grid grid-cols-2 gap-2 border-y border-border py-3">
          <div><div className="text-base font-semibold">{c.memberCount.toLocaleString()}</div><div className="text-xs text-muted">{c.memberCount === 1 ? 'Member' : 'Members'}</div></div>
          <div><div className="text-base font-semibold">{c.flairs.length}</div><div className="text-xs text-muted">Post flairs</div></div>
        </div>
        <dl className="space-y-1.5 text-xs text-muted">
          <div>
            <dt className="sr-only">Created</dt>
            <dd className="flex items-center gap-2"><CalendarDays size={14} aria-hidden="true" />Created {formatDate(c.createdAt)}</dd>
          </div>
          {c.visibility !== 'PUBLIC' && (
            <div>
              <dt className="sr-only">Visibility</dt>
              <dd className="flex items-center gap-2">
                {c.visibility === 'PRIVATE' ? <Lock size={14} aria-hidden="true" /> : <ShieldCheck size={14} aria-hidden="true" />}
                {c.visibility === 'PRIVATE' ? 'Private — members only' : 'Restricted — approved contributors post'}
              </dd>
            </div>
          )}
        </dl>
        {c.topic && (
          <Link to={`/communities?topic=${c.topic.slug}`} className="inline-block">
            <Badge tone="accent">{c.topic.name}</Badge>
          </Link>
        )}
        {c.tags.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {c.tags.map((t) => <Badge key={t}>#{t}</Badge>)}
          </div>
        )}
      </div>
    </RailPanel>
  );
}

export function RulesCard({ c }: { c: CommunityDetail }) {
  if (!c.rules.length) return null;
  return (
    <RailPanel title={`c/${c.slug} rules`}>
      <ol className="divide-y divide-border/70 px-0 pb-2">
        {c.rules.map((r, i) => (
          <li key={r.id}>
            <details className="group px-4 py-2.5">
              <summary className="flex cursor-pointer list-none items-start gap-2.5 text-sm font-medium">
                <span className="w-4 shrink-0 text-faint">{i + 1}</span>
                <span className="flex-1">{r.title}</span>
              </summary>
              {r.description && <p className="mt-1.5 pl-6 text-sm text-muted">{r.description}</p>}
            </details>
          </li>
        ))}
      </ol>
    </RailPanel>
  );
}

export function ModeratorsCard({ c }: { c: CommunityDetail }) {
  return (
    <RailPanel title="Moderators">
      <ul className="space-y-1 px-2 pb-2">
        {c.moderators.map((m) => (
          <li key={m.user.id}>
            <Link to={`/u/${m.user.username}`} className="flex items-center gap-2.5 rounded-md px-2 py-1.5 text-sm hover:bg-surface-2">
              <Avatar src={m.user.avatarUrl} name={m.user.username} size="sm" />
              <span className="flex-1 truncate">u/{m.user.username}</span>
              <span className="text-[11px] uppercase tracking-wide text-faint">{m.role.toLowerCase()}</span>
            </Link>
          </li>
        ))}
        {!c.moderators.length && <li className="px-2 py-1.5 text-sm text-muted">No moderators listed.</li>}
      </ul>
    </RailPanel>
  );
}
