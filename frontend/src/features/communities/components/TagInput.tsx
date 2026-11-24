import { X } from 'lucide-react';
import { useState, type KeyboardEvent } from 'react';
import { tagSchema } from '@/utils/validation';

/** Chip-style tag input: Enter or comma adds a tag; Backspace on empty removes the last one. */
export function TagInput({
  value,
  onChange,
  max = 5,
  id,
  describedBy,
}: {
  value: string[];
  onChange: (tags: string[]) => void;
  max?: number;
  id?: string;
  describedBy?: string;
}) {
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);

  const add = () => {
    const t = draft.trim().toLowerCase().replace(/^#/, '').replace(/\s+/g, '-');
    if (!t) return;
    if (value.includes(t)) return setDraft('');
    if (value.length >= max) return setError(`Up to ${max} tags`);
    const r = tagSchema.safeParse(t);
    if (!r.success) return setError(r.error.issues[0]?.message ?? 'Invalid tag');
    onChange([...value, t]);
    setDraft('');
    setError(null);
  };

  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      add();
    } else if (e.key === 'Backspace' && !draft && value.length) {
      onChange(value.slice(0, -1));
    }
  };

  return (
    <div>
      <div className="flex min-h-10 flex-wrap items-center gap-1.5 rounded-md border border-border bg-surface-2 px-2 py-1.5 focus-within:border-accent focus-within:ring-2 focus-within:ring-accent/25">
        {value.map((t) => (
          <span key={t} className="inline-flex items-center gap-1 rounded-sm bg-surface px-1.5 py-0.5 text-xs">
            #{t}
            <button type="button" aria-label={`Remove tag ${t}`} onClick={() => onChange(value.filter((x) => x !== t))} className="text-faint hover:text-text">
              <X size={12} />
            </button>
          </span>
        ))}
        <input
          id={id}
          aria-describedby={describedBy}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onKey}
          onBlur={add}
          placeholder={value.length ? '' : 'Add tags, press Enter'}
          className="min-w-[120px] flex-1 bg-transparent text-sm outline-none placeholder:text-faint"
        />
      </div>
      {error && <p role="alert" className="mt-1.5 text-xs text-danger">{error}</p>}
    </div>
  );
}
