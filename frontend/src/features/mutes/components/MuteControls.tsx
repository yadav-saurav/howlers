import { Volume2, VolumeX } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Card, CardHeader, Skeleton } from '@/components/ui/Display';
import { ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import { isCommunityMuted, isUserMuted, useMuteCommunity, useMuteUser, useMutes } from '../api';

/** Mute/unmute on a profile. Lighter than block: no confirmation, and the other person can't tell. */
export function MuteUserButton({ username }: { username: string }) {
  const mutes = useMutes();
  const muted = isUserMuted(mutes.data, username);
  const m = useMuteUser(username);
  return (
    <Button
      size="sm"
      variant={muted ? 'secondary' : 'ghost'}
      loading={m.isPending}
      disabled={mutes.isPending}
      title={muted ? undefined : 'Hide their posts from your feeds, collapse their comments and stop their notifications. They aren’t told.'}
      onClick={() =>
        m.mutate(!muted, {
          onSuccess: () => toast.success(muted ? `Unmuted u/${username}` : `Muted u/${username}. Only you can see this.`),
          onError: (e) => toast.error(errorMessage(e)),
        })
      }
    >
      {muted ? <Volume2 size={15} /> : <VolumeX size={15} />} {muted ? 'Unmute' : 'Mute'}
    </Button>
  );
}

/** Mute/unmute a community from its page: it leaves your Home and Popular/All feeds. */
export function MuteCommunityButton({ slug }: { slug: string }) {
  const mutes = useMutes();
  const muted = isCommunityMuted(mutes.data, slug);
  const m = useMuteCommunity(slug);
  return (
    <Button
      size="md"
      variant="secondary"
      className="w-10 px-0"
      loading={m.isPending}
      disabled={mutes.isPending}
      aria-label={muted ? `Unmute c/${slug}` : `Mute c/${slug}`}
      aria-pressed={muted}
      title={muted ? `Unmute c/${slug}` : `Mute c/${slug}: hide it from your Home and Popular feeds`}
      onClick={() =>
        m.mutate(!muted, {
          onSuccess: () => toast.success(muted ? `Unmuted c/${slug}` : `Muted c/${slug}. It won’t appear in your Home or Popular feeds.`),
          onError: (e) => toast.error(errorMessage(e)),
        })
      }
    >
      {muted ? <Volume2 size={17} /> : <VolumeX size={17} />}
    </Button>
  );
}

function UserRow({ username, avatarUrl }: { username: string; avatarUrl: string | null }) {
  const m = useMuteUser(username);
  return (
    <li className="flex items-center gap-3 px-4 py-2.5">
      <Avatar src={avatarUrl} name={username} size="sm" />
      <Link to={`/u/${username}`} className="flex-1 text-sm hover:underline">u/{username}</Link>
      <Button size="sm" variant="secondary" loading={m.isPending}
        onClick={() => m.mutate(false, { onSuccess: () => toast.success(`Unmuted u/${username}`), onError: (e) => toast.error(errorMessage(e)) })}>
        Unmute
      </Button>
    </li>
  );
}

function CommunityRow({ slug, iconUrl }: { slug: string; iconUrl: string | null }) {
  const m = useMuteCommunity(slug);
  return (
    <li className="flex items-center gap-3 px-4 py-2.5">
      <Avatar src={iconUrl} name={slug} size="sm" />
      <Link to={`/c/${slug}`} className="flex-1 text-sm hover:underline">c/{slug}</Link>
      <Button size="sm" variant="secondary" loading={m.isPending}
        onClick={() => m.mutate(false, { onSuccess: () => toast.success(`Unmuted c/${slug}`), onError: (e) => toast.error(errorMessage(e)) })}>
        Unmute
      </Button>
    </li>
  );
}

/** Settings → Muted. Mutes are private; nobody is told. */
export function MutedSettings() {
  const q = useMutes();
  return (
    <div className="space-y-4">
      <p className="text-sm text-muted">
        Muting is private and lighter than blocking. Muted people’s posts leave your feeds, their comments arrive collapsed and they can’t
        notify you, but they can still see and reply to your posts. Muted communities leave your Home and Popular/All feeds; you can still visit them.
      </p>
      {q.isPending && <Skeleton className="h-24 rounded-2xl" />}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && (
        <>
          <Card>
            <CardHeader title="Muted people" />
            {q.data.users.length === 0 ? (
              <p className="px-4 py-3 text-sm text-muted">You haven’t muted anyone. Use Mute on someone’s profile.</p>
            ) : (
              <ul className="divide-y divide-border">{q.data.users.map((u) => <UserRow key={u.id} username={u.username} avatarUrl={u.avatarUrl} />)}</ul>
            )}
          </Card>
          <Card>
            <CardHeader title="Muted communities" />
            {q.data.communities.length === 0 ? (
              <p className="px-4 py-3 text-sm text-muted">No muted communities. Use the mute button on a community’s page.</p>
            ) : (
              <ul className="divide-y divide-border">{q.data.communities.map((c) => <CommunityRow key={c.id} slug={c.slug} iconUrl={c.iconUrl} />)}</ul>
            )}
          </Card>
        </>
      )}
    </div>
  );
}
