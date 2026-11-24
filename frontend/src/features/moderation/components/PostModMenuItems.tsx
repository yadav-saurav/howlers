import { Ban, CheckCircle2, Lock, LockOpen, Pin, PinOff, ShieldX } from 'lucide-react';
import { MenuItem, MenuLabel, MenuSeparator } from '@/components/ui/Menu';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { PostSummary } from '@/types/api';
import type { PostModVerb } from '@/types/moderation';
import { modApi, useModMutation } from '../api';

const DONE: Record<PostModVerb, string> = {
  remove: 'Post removed',
  approve: 'Post approved',
  lock: 'Comments locked',
  unlock: 'Comments unlocked',
  pin: 'Post pinned',
  unpin: 'Post unpinned',
};

/** Moderator section inside PostMenu. Remove and Ban open dialogs owned by the parent (menus close on select). */
export function PostModMenuItems({ post, onRemove, onBan }: { post: PostSummary; onRemove: () => void; onBan: () => void }) {
  const act = useModMutation(post.community.slug, (verb: PostModVerb) => modApi.post(post.id, verb));
  const run = (verb: PostModVerb) => act.mutate(verb, { onSuccess: () => toast.success(DONE[verb]), onError: (e) => toast.error(errorMessage(e)) });
  return (
    <>
      <MenuSeparator />
      <MenuLabel>Moderation</MenuLabel>
      {post.pending ? (
        <>
          <MenuItem icon={<CheckCircle2 />} onSelect={() => run('approve')}>Approve and publish</MenuItem>
          <MenuItem icon={<ShieldX />} danger onSelect={onRemove}>Remove post</MenuItem>
        </>
      ) : post.removed ? (
        <MenuItem icon={<CheckCircle2 />} onSelect={() => run('approve')}>Approve (restore)</MenuItem>
      ) : (
        <>
          <MenuItem icon={<ShieldX />} danger onSelect={onRemove}>Remove post</MenuItem>
          <MenuItem icon={<CheckCircle2 />} onSelect={() => run('approve')}>Approve (dismiss reports)</MenuItem>
        </>
      )}
      {post.locked ? (
        <MenuItem icon={<LockOpen />} onSelect={() => run('unlock')}>Unlock comments</MenuItem>
      ) : (
        <MenuItem icon={<Lock />} onSelect={() => run('lock')}>Lock comments</MenuItem>
      )}
      {post.pinned ? (
        <MenuItem icon={<PinOff />} onSelect={() => run('unpin')}>Unpin</MenuItem>
      ) : (
        <MenuItem icon={<Pin />} onSelect={() => run('pin')}>Pin to community</MenuItem>
      )}
      {post.author && <MenuItem icon={<Ban />} danger onSelect={onBan}>Ban u/{post.author.username}</MenuItem>}
    </>
  );
}
