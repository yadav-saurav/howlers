import { Gavel, ShieldAlert } from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Badge, Skeleton } from '@/components/ui/Display';
import { Textarea } from '@/components/ui/Field';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { modApi, modKeys, useMyModAction } from '@/features/moderation/api';
import { affectedUserHeadline, formatBanExpiry } from '@/features/moderation/helpers';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { ApiError, errorMessage } from '@/services/apiError';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { formatDate, timeAgo } from '@/utils/format';

/** What a moderator did to your content or account, and a way to appeal it once. */
export default function ModActionPage() {
  const { id: raw = '' } = useParams();
  const id = Number(raw);
  const q = useMyModAction(id);
  const qc = useQueryClient();
  const [message, setMessage] = useState('');
  const appeal = useMutation({ mutationFn: () => modApi.appeal(id, message.trim()), onSuccess: () => qc.invalidateQueries({ queryKey: modKeys.myAction(id) }) });
  useDocumentTitle('Moderation decision');

  if (q.isPending) return <div className="mx-auto max-w-2xl space-y-3 px-4 py-8"><Skeleton className="h-8 w-2/3" /><Skeleton className="h-32" /></div>;
  if (q.isError) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-8">
        {q.error instanceof ApiError && (q.error.status === 404 || q.error.status === 403)
          ? <EmptyState icon={ShieldAlert} title="Nothing to show here" description="This decision doesn't exist or isn't about you." />
          : <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      </div>
    );
  }
  const { action: a, community, appeal: existing, canAppeal } = q.data;
  const ban = a.action === 'BAN';

  return (
    <div className="mx-auto w-full max-w-2xl px-4 py-8">
      <div className="mb-1 flex items-center gap-2 text-sm text-muted">
        <Avatar src={community.iconUrl} name={community.slug} size="xs" />
        <Link to={`/c/${community.slug}`} className="font-semibold text-text hover:underline">c/{community.slug}</Link>
        <span>· {timeAgo(a.createdAt)}</span>
        {a.reversed && <Badge tone="accent" className="rounded-full px-2">Reversed</Badge>}
      </div>
      <h1 className="text-2xl font-bold">{affectedUserHeadline(a.action, community.slug)}</h1>
      <div className="mt-4 space-y-2 rounded-2xl border border-border p-4 text-sm">
        {a.targetTitle && <p><span className="text-muted">Content: </span>{a.postId ? <Link to={`/c/${community.slug}/p/${a.postId}${a.commentId ? `#c-${a.commentId}` : ''}`} className="font-semibold hover:underline">{a.targetTitle}</Link> : a.targetTitle}</p>}
        <p><span className="text-muted">Reason: </span>{a.reason || 'No reason given'}</p>
        {ban && <p><span className="text-muted">Duration: </span>{formatBanExpiry(a.expiresAt)}</p>}
        {/* Decisions are made on behalf of the moderation team (D-014): never name the individual moderator here. */}
        <p className="text-xs text-muted">Decided on {formatDate(a.createdAt)} by the c/{community.slug} moderators.</p>
      </div>

      <section className="mt-6">
        <h2 className="mb-2 flex items-center gap-2 text-lg font-bold"><Gavel size={18} /> Appeal</h2>
        {existing ? (
          <div className="space-y-2 rounded-2xl bg-surface-2/60 p-4 text-sm">
            <div className="flex items-center gap-2">
              <span className="text-muted">Status:</span>
              <Badge tone={existing.status === 'PENDING' ? 'warning' : existing.status === 'ACCEPTED' ? 'accent' : 'neutral'} className="rounded-full px-2">
                {existing.status === 'PENDING' ? 'Waiting for moderators' : existing.status === 'ACCEPTED' ? 'Accepted. The decision was reversed' : 'Rejected'}
              </Badge>
            </div>
            <p className="whitespace-pre-wrap">“{existing.message}”</p>
            {existing.response && <p className="text-muted">Moderators replied: “{existing.response}”</p>}
          </div>
        ) : canAppeal ? (
          <form className="space-y-2" onSubmit={(e) => { e.preventDefault(); appeal.mutate(undefined, { onSuccess: () => toast.success('Appeal sent to the moderators'), onError: (er) => toast.error(errorMessage(er)) }); }}>
            <p className="text-sm text-muted">Explain why you think this decision should be reversed. You can appeal once.</p>
            <Textarea value={message} onChange={(e) => setMessage(e.target.value)} rows={5} minLength={10} maxLength={2000} aria-label="Appeal message" />
            <div className="flex items-center justify-between text-xs text-faint">
              <span>{message.trim().length}/2000 (minimum 10)</span>
              <Button type="submit" loading={appeal.isPending} disabled={message.trim().length < 10}>Send appeal</Button>
            </div>
          </form>
        ) : (
          <p className="text-sm text-muted">{a.reversed ? 'This decision has already been reversed.' : 'This decision can’t be appealed.'}</p>
        )}
      </section>
    </div>
  );
}
