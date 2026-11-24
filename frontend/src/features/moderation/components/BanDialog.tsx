import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Input, Select, Textarea } from '@/components/ui/Field';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import { modApi, useModMutation } from '../api';
import { BAN_DURATIONS } from '../helpers';

/** Ban a user from one community. `username` prefills (and locks) the target when banning from a post/comment. */
export function BanDialog({ slug, username, open, onOpenChange }: { slug: string; username?: string; open: boolean; onOpenChange: (o: boolean) => void }) {
  const [user, setUser] = useState(username ?? '');
  const [reason, setReason] = useState('');
  const [duration, setDuration] = useState('7');
  const ban = useModMutation(slug, (b: { username: string; reason: string; days: number | null }) => modApi.ban(slug, b));
  const target = (username ?? user).trim().replace(/^u\//, '');

  const submit = () => {
    const days = BAN_DURATIONS.find((d) => d.value === duration)?.days ?? null;
    ban.mutate(
      { username: target, reason: reason.trim(), days },
      {
        onSuccess: () => {
          toast.success(`u/${target} is banned from c/${slug}`);
          onOpenChange(false);
          setReason('');
          if (!username) setUser('');
        },
        onError: (e) => toast.error(errorMessage(e)),
      },
    );
  };

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={username ? `Ban u/${username} from c/${slug}` : `Ban a user from c/${slug}`}
      description="Banned users can still read public content, but can't post, comment, vote or take part in Pulses here."
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button variant="danger" loading={ban.isPending} disabled={!target || !reason.trim()} onClick={submit}>Ban</Button>
        </>
      }
    >
      <div className="space-y-3">
        {!username && (
          <label className="block text-sm font-semibold">
            Username
            <Input value={user} onChange={(e) => setUser(e.target.value)} placeholder="username" autoComplete="off" className="mt-1" />
          </label>
        )}
        <label className="block text-sm font-semibold">
          Reason (shown to the user)
          <Textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={300} className="mt-1" />
        </label>
        <label className="block text-sm font-semibold">
          Duration
          <Select value={duration} onChange={(e) => setDuration(e.target.value)} className="mt-1">
            {BAN_DURATIONS.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
          </Select>
        </label>
      </div>
    </Dialog>
  );
}
