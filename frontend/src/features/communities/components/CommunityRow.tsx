import { Lock, ShieldCheck } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Avatar, Badge } from '@/components/ui/Display';
import type { CommunitySummary } from '@/types/api';
import { pluralize } from '@/utils/format';
import { JoinButton } from './JoinButton';

export function CommunityRow({ c, compact }: { c: CommunitySummary; compact?: boolean }) {
  return (
    <div className="flex items-start gap-3 px-4 py-3">
      <Link to={`/c/${c.slug}`} className="shrink-0">
        <Avatar src={c.iconUrl} name={c.slug} size={compact ? 'sm' : 'md'} square />
      </Link>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <Link to={`/c/${c.slug}`} className="truncate text-sm font-semibold hover:underline">
            c/{c.slug}
          </Link>
          {c.visibility === 'PRIVATE' && <Badge><Lock size={10} /> Private</Badge>}
          {c.visibility === 'RESTRICTED' && <Badge><ShieldCheck size={10} /> Restricted</Badge>}
        </div>
        <div className="text-xs text-faint">
          {pluralize(c.memberCount, 'member')}
          {c.topic && ` · ${c.topic.name}`}
        </div>
        {!compact && c.description && <p className="mt-1 line-clamp-2 text-sm text-muted">{c.description}</p>}
      </div>
      <JoinButton slug={c.slug} isMember={c.isMember} />
    </div>
  );
}
