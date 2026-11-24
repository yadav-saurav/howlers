import { Plus, X } from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Input, Select, Textarea } from '@/components/ui/Field';
import { Switch } from '@/components/ui/Switch';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { PulseType } from '@/types/moderation';
import { pulseApi, usePulseMutation } from '../api';
import { DURATIONS, TYPE_LABELS } from '../helpers';

const HINTS: Record<PulseType, string> = {
  POLL: 'A quick question with fixed answers.',
  PREDICTION: 'Members forecast an outcome; you resolve it later and everyone sees who called it.',
  OPINION: 'Take the temperature of the community on a question.',
  DEBATE: 'Pick a side and give an argument; results show where the community stands.',
};

/** Moderator form to start a Pulse in a community. */
export function CreatePulseDialog({ slug, open, onOpenChange }: { slug: string; open: boolean; onOpenChange: (o: boolean) => void }) {
  const [type, setType] = useState<PulseType>('POLL');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [options, setOptions] = useState(['', '']);
  const [multiple, setMultiple] = useState(false);
  const [anonymous, setAnonymous] = useState(false);
  const [allowChange, setAllowChange] = useState(true);
  const [duration, setDuration] = useState(72);
  const create = usePulseMutation(() =>
    pulseApi.create(slug, {
      type,
      title: title.trim(),
      description: description.trim() || undefined,
      options: options.map((o) => o.trim()).filter(Boolean),
      multiple: type === 'PREDICTION' ? false : multiple,
      anonymous,
      allowChange,
      durationHours: duration,
    }),
  );
  const filled = options.filter((o) => o.trim()).length;
  const valid = title.trim().length > 0 && filled >= 2;

  const reset = () => {
    setTitle('');
    setDescription('');
    setOptions(['', '']);
    setMultiple(false);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title="New Pulse"
      description={HINTS[type]}
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button
            disabled={!valid}
            loading={create.isPending}
            onClick={() => create.mutate(undefined, { onSuccess: () => { toast.success('Pulse started'); reset(); onOpenChange(false); }, onError: (e) => toast.error(errorMessage(e)) })}
          >
            Start Pulse
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label="Pulse type">
          {(Object.keys(TYPE_LABELS) as PulseType[]).map((t) => (
            <button key={t} type="button" role="radio" aria-checked={type === t} onClick={() => setType(t)}
              className={`rounded-full px-3 py-1.5 text-xs font-semibold ${type === t ? 'bg-brand text-brand-fg' : 'bg-surface-2 text-muted hover:text-text'}`}>
              {TYPE_LABELS[t]}
            </button>
          ))}
        </div>
        <label className="block text-sm font-semibold">Question<Input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} className="mt-1" /></label>
        <label className="block text-sm font-semibold">Context (optional)<Textarea value={description} onChange={(e) => setDescription(e.target.value)} maxLength={1000} rows={2} className="mt-1" /></label>
        <div>
          <div className="mb-1 text-sm font-semibold">Options</div>
          <div className="space-y-1.5">
            {options.map((o, i) => (
              <div key={i} className="flex gap-2">
                <Input value={o} onChange={(e) => setOptions(options.map((x, j) => (j === i ? e.target.value : x)))} maxLength={120} placeholder={`Option ${i + 1}`} aria-label={`Option ${i + 1}`} />
                {options.length > 2 && (
                  <button type="button" aria-label={`Remove option ${i + 1}`} onClick={() => setOptions(options.filter((_, j) => j !== i))} className="rounded-full p-2 text-muted hover:bg-surface-2"><X size={15} /></button>
                )}
              </div>
            ))}
          </div>
          {options.length < 8 && (
            <button type="button" onClick={() => setOptions([...options, ''])} className="mt-1.5 inline-flex items-center gap-1 text-xs font-semibold text-brand hover:underline"><Plus size={13} /> Add option</button>
          )}
        </div>
        <div className="grid gap-2 sm:grid-cols-2">
          {type !== 'PREDICTION' && <Switch label="Allow multiple choices" checked={multiple} onCheckedChange={setMultiple} />}
          <Switch label="Anonymous voting" checked={anonymous} onCheckedChange={setAnonymous} />
          <Switch label="Let people change their vote" checked={allowChange} onCheckedChange={setAllowChange} />
        </div>
        <label className="block text-sm font-semibold">
          Duration
          <Select value={duration} onChange={(e) => setDuration(Number(e.target.value))} className="mt-1">
            {DURATIONS.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
          </Select>
        </label>
      </div>
    </Dialog>
  );
}
