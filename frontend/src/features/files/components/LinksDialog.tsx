import { ChevronDown, KeyRound, Link2, Link2Off, Lock } from 'lucide-react';
import { useState } from 'react';
import { Avatar, Badge, Skeleton } from '@/components/ui/Display';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { ErrorState, LoadMore } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { FileItem, ShareLink } from '@/types/files';
import { cn, formatDate, timeAgo } from '@/utils/format';
import { filesApi, useAccessLog, useFileLinks, useFileMutation } from '../api';
import { EVENT_TEXT, STATUS_TEXT, downloadsText, isFailureEvent } from '../helpers';

const ago = (iso: string) => {
  const r = timeAgo(iso);
  return r === 'just now' ? r : `${r} ago`;
};

function AccessLog({ fileId, linkId }: { fileId: number; linkId: number }) {
  const q = useAccessLog(fileId, linkId, true);
  if (q.isPending) return <Skeleton className="h-16" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const items = q.data.pages.flatMap((p) => p.items);
  if (items.length === 0) return <p className="py-2 text-xs text-faint">Nobody has opened this link yet.</p>;
  return (
    <div>
      <ul className="divide-y divide-border/60 text-xs">
        {items.map((a) => (
          <li key={a.id} className="flex items-center gap-2 py-1.5">
            {a.viewer ? (
              <Avatar src={a.viewer.avatarUrl} name={a.viewer.username} size="xs" />
            ) : (
              <span className="inline-flex h-5 w-5 items-center justify-center rounded-full bg-surface-2 text-[9px] text-faint" aria-hidden>
                ?
              </span>
            )}
            <span className="min-w-0 flex-1 truncate">
              <span className="font-medium text-text">{a.viewer ? `u/${a.viewer.username}` : 'Anonymous'}</span>
              {!a.viewer && a.visitor && <span className="text-faint"> · visitor {a.visitor.slice(0, 6)}</span>}
              <span className={cn(isFailureEvent(a.event) ? 'text-warning' : 'text-muted')}> · {EVENT_TEXT[a.event]}</span>
            </span>
            <time dateTime={a.at} title={new Date(a.at).toLocaleString()} className="shrink-0 text-faint">
              {timeAgo(a.at)}
            </time>
          </li>
        ))}
      </ul>
      <LoadMore hasMore={!!q.hasNextPage} loading={q.isFetchingNextPage} onLoad={() => q.fetchNextPage()} />
    </div>
  );
}

function LinkRow({ file, link }: { file: FileItem; link: ShareLink }) {
  const [openLog, setOpenLog] = useState(false);
  const revoke = useFileMutation(() => filesApi.revokeLink(file.id, link.id));
  const active = link.status === 'ACTIVE';
  return (
    <li className="py-3">
      <div className="flex flex-wrap items-center gap-2">
        {active ? <Link2 size={15} className="text-accent" aria-hidden /> : <Link2Off size={15} className="text-faint" aria-hidden />}
        <span className="font-mono text-xs text-muted">/s/…{link.tokenHint}</span>
        <Badge tone={active ? 'accent' : 'neutral'}>{STATUS_TEXT[link.status]}</Badge>
        {link.hasPassword && (
          <Badge>
            <Lock size={10} aria-hidden /> Password
          </Badge>
        )}
        {link.keyMode && (
          <Badge tone="info">
            <KeyRound size={10} aria-hidden /> {link.keyMode === 'FRAGMENT' ? 'Key in link' : 'Passphrase'}
          </Badge>
        )}
        <span className="flex-1" />
        {active && (
          <Button
            variant="ghost"
            size="sm"
            loading={revoke.isPending}
            onClick={() =>
              revoke.mutate(undefined, {
                onSuccess: () => toast.success('Link turned off. It stops working immediately.'),
                onError: (e) => toast.error(errorMessage(e)),
              })
            }
          >
            Turn off
          </Button>
        )}
      </div>
      <p className="mt-1 text-xs text-faint">
        Created {formatDate(link.createdAt)} · {link.expiresAt ? `${link.status === 'EXPIRED' ? 'expired' : 'expires'} ${new Date(link.expiresAt).toLocaleString()}` : 'no expiry'} ·{' '}
        {downloadsText(link)}
        {link.lastAccessAt && <> · last activity {ago(link.lastAccessAt)}</>}
      </p>
      <button
        type="button"
        className="mt-1.5 inline-flex items-center gap-1 text-xs font-medium text-brand hover:underline"
        aria-expanded={openLog}
        onClick={() => setOpenLog((v) => !v)}
      >
        <ChevronDown size={12} className={cn('transition-transform', openLog && 'rotate-180')} aria-hidden /> Access log
      </button>
      {openLog && (
        <div className="mt-2 rounded-lg bg-surface-2/60 px-3 py-1">
          <AccessLog fileId={file.id} linkId={link.id} />
        </div>
      )}
    </li>
  );
}

/** Every link for a file, its status and access log. Visitors are shown as a signed-in user or an anonymous daily hash. */
export function LinksDialog({ file, open, onOpenChange, onNewLink }: { file: FileItem; open: boolean; onOpenChange: (v: boolean) => void; onNewLink: () => void }) {
  const q = useFileLinks(file.id, open);
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      size="lg"
      title="Links and access"
      description={file.name}
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Close
          </Button>
          <Button onClick={onNewLink}>
            <Link2 size={14} /> New link
          </Button>
        </>
      }
    >
      {q.isPending && <Skeleton className="h-24" />}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && q.data.length === 0 && <p className="py-4 text-sm text-muted">No links yet. Only you can open this file.</p>}
      {q.data && q.data.length > 0 && (
        <>
          <ul className="divide-y divide-border">{q.data.map((l) => <LinkRow key={l.id} file={file} link={l} />)}</ul>
          <p className="mt-3 text-xs text-faint">
            We don't store IP addresses. Anonymous visitors appear as a short code derived from their address and the date with a secret
            salt, so you can tell repeat attempts apart within a day but not identify anyone.
          </p>
        </>
      )}
    </Dialog>
  );
}
