import { HeartHandshake, Users } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { Input } from '@/components/ui/Field';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { ModPeer } from '@/types/recognition';
import { useEndorse, usePeers } from '../api';
import { periodLabel } from '../logic';

function PeerRow({ slug, peer }: { slug: string; peer: ModPeer }) {
  const [note, setNote] = useState(peer.note ?? '');
  const endorse = useEndorse(slug);
  const name = peer.moderator.username;
  const run = (withdraw: boolean) =>
    endorse.mutate(
      { username: name, note: note.trim() || undefined, withdraw },
      { onSuccess: () => toast.success(withdraw ? 'Endorsement withdrawn' : `Endorsed u/${name}`), onError: (e) => toast.error(errorMessage(e)) },
    );
  return (
    <li className="space-y-2 p-4">
      <div className="flex items-center gap-2">
        <Avatar src={peer.moderator.avatarUrl} name={name} size="sm" />
        <Link to={`/u/${name}`} className="text-sm font-semibold hover:underline">u/{name}</Link>
        <span className="ml-auto text-xs text-faint">{peer.endorsementsThisPeriod} this month</span>
      </div>
      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          value={note}
          onChange={(e) => setNote(e.target.value)}
          maxLength={280}
          placeholder="Optional note: what did they do well?"
          aria-label={`Note for u/${name}`}
          className="flex-1"
        />
        <div className="flex gap-2">
          <Button size="sm" variant={peer.endorsedByMe ? 'secondary' : 'primary'} loading={endorse.isPending} onClick={() => run(false)}>
            <HeartHandshake size={15} /> {peer.endorsedByMe ? 'Update note' : 'Helpful +1'}
          </Button>
          {peer.endorsedByMe && (
            <Button size="sm" variant="ghost" disabled={endorse.isPending} onClick={() => run(true)}>Withdraw</Button>
          )}
        </div>
      </div>
    </li>
  );
}

/** Mod-tools tab: one "+1 helpful" per co-moderator per month, feeding the peer component of the score. */
export function PeerEndorsements({ slug }: { slug: string }) {
  const q = usePeers(slug);
  if (q.isPending) return <Skeleton className="h-32 rounded-2xl" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const { peers, period } = q.data;
  return (
    <div className="space-y-3">
      <p className="text-sm text-muted">
        Endorse co-moderators who helped this month ({periodLabel(period)}). It counts toward the peer part of their
        <Link to={`/leaderboard?community=${slug}`} className="mx-1 underline">moderator score</Link>
        and resets each month. Notes are saved with your endorsement and shown only to you.
      </p>
      {peers.length === 0 ? (
        <EmptyState icon={Users} title="No co-moderators yet" description="Endorsements appear once this community has other moderators." />
      ) : (
        <ul className="divide-y divide-border rounded-2xl border border-border">
          {peers.map((p) => <PeerRow key={p.moderator.id} slug={slug} peer={p} />)}
        </ul>
      )}
    </div>
  );
}
