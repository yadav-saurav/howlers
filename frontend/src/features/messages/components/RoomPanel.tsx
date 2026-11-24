import { useQueryClient } from '@tanstack/react-query';
import { Crown, LogOut, MoreHorizontal, Shield, Trash2, UserMinus, UserPlus } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Avatar, Badge, Skeleton } from '@/components/ui/Display';
import { Field, Input } from '@/components/ui/Field';
import { Menu, MenuContent, MenuItem, MenuTrigger } from '@/components/ui/Menu';
import { ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import { useAuthStore } from '@/store/authStore';
import type { RoomDetail, RoomMember } from '@/types/messages';
import { displayName } from '@/utils/format';
import { forgetRoom, roomApi, useRoom, useRoomMutation } from '../api';
import { ROLE_LABEL, canManage, canRemove } from '../rooms';
import { OnlineDot } from './bits';
import { IconChoice } from './IconChoice';
import { PeoplePicker } from './PeoplePicker';

function MemberRow({ d, m, meId }: { d: RoomDetail; m: RoomMember; meId: number | undefined }) {
  const self = m.user.id === meId;
  const remove = useRoomMutation(d.id, () => roomApi.removeMember(d.id, m.user.id));
  const role = useRoomMutation(d.id, (r: 'ADMIN' | 'MEMBER') => roomApi.setRole(d.id, m.user.id, r));
  const transfer = useRoomMutation(d.id, () => roomApi.transfer(d.id, m.user.id));
  const owner = d.myRole === 'OWNER';
  const removable = canRemove(d.myRole, m.role, self);
  const fail = (e: unknown) => toast.error(errorMessage(e));
  const hasActions = !self && (removable || (owner && m.role !== 'OWNER'));
  return (
    <li className="flex items-center gap-3 py-2">
      <span className="relative shrink-0">
        <Avatar src={m.user.avatarUrl} name={m.user.username} size="sm" />
        {m.online && <OnlineDot className="absolute -bottom-0.5 -right-0.5" />}
      </span>
      <span className="min-w-0 flex-1">
        <Link to={`/u/${m.user.username}`} className="block truncate text-sm font-medium hover:underline">
          {displayName(m.user)}{self && <span className="text-muted"> (you)</span>}
        </Link>
        <span className="block truncate text-xs text-muted">u/{m.user.username}{m.online ? ' · online' : ''}</span>
      </span>
      {m.role !== 'MEMBER' && (
        <Badge tone={m.role === 'OWNER' ? 'accent' : 'info'} className="rounded-full px-2">{ROLE_LABEL[m.role]}</Badge>
      )}
      {hasActions && (
        <Menu>
          <MenuTrigger asChild>
            <button type="button" aria-label={`Actions for u/${m.user.username}`} className="rounded-full p-1.5 text-muted hover:bg-surface-2 hover:text-text">
              <MoreHorizontal size={16} />
            </button>
          </MenuTrigger>
          <MenuContent>
            {owner && m.role === 'MEMBER' && (
              <MenuItem icon={<Shield />} onSelect={() => role.mutate('ADMIN', { onError: fail })}>Make admin</MenuItem>
            )}
            {owner && m.role === 'ADMIN' && (
              <MenuItem icon={<Shield />} onSelect={() => role.mutate('MEMBER', { onError: fail })}>Make member</MenuItem>
            )}
            {owner && m.role !== 'OWNER' && (
              <MenuItem icon={<Crown />} onSelect={() => {
                if (window.confirm(`Make u/${m.user.username} the owner? You'll stay in the room as an admin.`)) {
                  transfer.mutate(undefined, { onSuccess: () => toast.success('Ownership handed over'), onError: fail });
                }
              }}>Make owner</MenuItem>
            )}
            {removable && (
              <MenuItem danger icon={<UserMinus />} onSelect={() => remove.mutate(undefined, { onSuccess: () => toast.success(`Removed u/${m.user.username}`), onError: fail })}>
                Remove from room
              </MenuItem>
            )}
          </MenuContent>
        </Menu>
      )}
    </li>
  );
}

function Settings({ d }: { d: RoomDetail }) {
  const [name, setName] = useState(d.name);
  const [icon, setIcon] = useState<string | null>(d.iconEmoji);
  useEffect(() => {
    setName(d.name);
    setIcon(d.iconEmoji);
  }, [d.name, d.iconEmoji]);
  const save = useRoomMutation(d.id, () => roomApi.update(d.id, { name: name.trim(), iconEmoji: icon ?? '' }));
  const dirty = name.trim() !== d.name || icon !== d.iconEmoji;
  return (
    <section aria-labelledby="room-settings" className="space-y-3">
      <h3 id="room-settings" className="text-sm font-semibold">Room settings</h3>
      <Field label="Name">
        {(p) => <Input {...p} value={name} maxLength={60} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <IconChoice value={icon} onChange={setIcon} />
      <Button size="sm" disabled={!dirty || !name.trim()} loading={save.isPending}
        onClick={() => save.mutate(undefined, { onSuccess: () => toast.success('Room updated'), onError: (e) => toast.error(errorMessage(e)) })}>
        Save changes
      </Button>
    </section>
  );
}

function AddMembers({ d }: { d: RoomDetail }) {
  const [people, setPeople] = useState<string[]>([]);
  const add = useRoomMutation(d.id, () => roomApi.addMembers(d.id, people));
  const left = d.maxMembers - d.members.length;
  return (
    <section aria-labelledby="room-add" className="space-y-2">
      <h3 id="room-add" className="text-sm font-semibold">Add people <span className="text-xs font-normal text-faint">({left} places left)</span></h3>
      <PeoplePicker value={people} onChange={setPeople} exclude={d.members.map((m) => m.user.username)} />
      <Button size="sm" variant="secondary" disabled={people.length === 0 || left <= 0} loading={add.isPending}
        onClick={() => add.mutate(undefined, {
          onSuccess: () => {
            toast.success(people.length === 1 ? 'Added 1 person' : `Added ${people.length} people`);
            setPeople([]);
          },
          onError: (e) => toast.error(errorMessage(e)),
        })}>
        <UserPlus size={15} /> Add
      </Button>
    </section>
  );
}

/** Members, roles and room settings (D-043). The server enforces every permission; the UI only hides what you can't do. */
export function RoomPanel({ id, open, onOpenChange }: { id: number; open: boolean; onOpenChange: (o: boolean) => void }) {
  const meId = useAuthStore((s) => s.user?.id);
  const room = useRoom(id, open);
  const qc = useQueryClient();
  const navigate = useNavigate();
  const d = room.data;
  const [busy, setBusy] = useState(false);

  const leaveOrDelete = async (kind: 'leave' | 'delete') => {
    const question = kind === 'leave'
      ? 'Leave this room? You lose its history and need to be added again to come back.'
      : 'Delete this room for everyone? Its messages and images are removed and this can’t be undone.';
    if (!window.confirm(question)) return;
    setBusy(true);
    try {
      await (kind === 'leave' ? roomApi.leave(id) : roomApi.remove(id));
      onOpenChange(false);
      forgetRoom(qc, id);
      navigate('/messages');
      toast.success(kind === 'leave' ? 'You left the room' : 'Room deleted');
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={d ? `${d.iconEmoji ? `${d.iconEmoji} ` : ''}${d.name}` : 'Room'}
      description={d ? `${d.members.length} of ${d.maxMembers} members` : undefined} size="lg">
      <div className="max-h-[70vh] space-y-5 overflow-y-auto p-4">
        {room.isPending ? (
          <div className="space-y-2">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-10 w-full" />)}</div>
        ) : room.isError ? (
          <ErrorState error={room.error} onRetry={() => room.refetch()} />
        ) : d ? (
          <>
            <section aria-labelledby="room-members">
              <h3 id="room-members" className="text-sm font-semibold">Members</h3>
              <ul className="divide-y divide-border">
                {d.members.map((m) => <MemberRow key={m.user.id} d={d} m={m} meId={meId} />)}
              </ul>
            </section>
            {canManage(d.myRole) && <AddMembers d={d} />}
            {canManage(d.myRole) && <Settings d={d} />}
            <section className="flex flex-wrap gap-2 border-t border-border pt-4">
              <Button size="sm" variant="secondary" loading={busy} onClick={() => void leaveOrDelete('leave')}>
                <LogOut size={15} /> Leave room
              </Button>
              {d.myRole === 'OWNER' && (
                <Button size="sm" variant="danger" loading={busy} onClick={() => void leaveOrDelete('delete')}>
                  <Trash2 size={15} /> Delete room
                </Button>
              )}
              {d.myRole === 'OWNER' && (
                <p className="w-full text-xs text-faint">If you leave, the longest-standing admin (or member) becomes the owner.</p>
              )}
            </section>
          </>
        ) : null}
      </div>
    </Dialog>
  );
}
