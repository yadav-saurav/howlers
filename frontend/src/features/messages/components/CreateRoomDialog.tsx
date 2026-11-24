import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Field, Input } from '@/components/ui/Field';
import { errorMessage } from '@/services/apiError';
import { useCreateRoom } from '../api';
import { ROOM_MAX_MEMBERS, validateRoomDraft } from '../rooms';
import { IconChoice } from './IconChoice';
import { PeoplePicker } from './PeoplePicker';

/** New pack room: a name, an optional icon and at least two other people (D-043). */
export function CreateRoomDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const [name, setName] = useState('');
  const [icon, setIcon] = useState<string | null>('🐺');
  const [people, setPeople] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const create = useCreateRoom();
  const navigate = useNavigate();

  const reset = () => {
    setName('');
    setIcon('🐺');
    setPeople([]);
    setError(null);
  };

  const submit = () => {
    const problem = validateRoomDraft(name, people);
    if (problem) {
      setError(problem);
      return;
    }
    setError(null);
    create.mutate(
      { name: name.trim(), iconEmoji: icon, usernames: people },
      {
        onSuccess: (s) => {
          onOpenChange(false);
          reset();
          navigate(`/messages/${s.id}`);
        },
        onError: (e) => setError(errorMessage(e)),
      },
    );
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        onOpenChange(o);
        if (!o) setError(null);
      }}
      title="New pack room"
      description={`A group conversation for 3 to ${ROOM_MAX_MEMBERS} people. Adding someone follows the same rules as messaging them first: blocks and their message settings apply.`}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button loading={create.isPending} onClick={submit}>Create room</Button>
        </div>
      }
    >
      <form
        className="space-y-4 p-4"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <Field label="Room name">
          {(p) => <Input {...p} value={name} onChange={(e) => setName(e.target.value)} maxLength={60} placeholder="e.g. Night shift" />}
        </Field>
        <IconChoice value={icon} onChange={setIcon} />
        <div>
          <p className="mb-1.5 text-sm font-medium">People <span className="text-xs font-normal text-faint">({people.length} added, at least 2)</span></p>
          <PeoplePicker value={people} onChange={setPeople} />
        </div>
        {error && <p role="alert" className="text-sm text-danger">{error}</p>}
      </form>
    </Dialog>
  );
}
