import { useId, useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Textarea } from '@/components/ui/Field';
import { cn } from '@/utils/format';
import { Markdown } from './Markdown';

const MAX = 10_000;

/** Markdown comment composer with Write/Preview toggle. */
export function CommentComposer({
  initial = '',
  submitLabel = 'Comment',
  placeholder = 'Add to the discussion. Markdown supported; @mention people.',
  loading,
  onSubmit,
  onCancel,
  autoFocus,
}: {
  initial?: string;
  submitLabel?: string;
  placeholder?: string;
  loading?: boolean;
  onSubmit: (body: string, reset: () => void) => void;
  onCancel?: () => void;
  autoFocus?: boolean;
}) {
  const [body, setBody] = useState(initial);
  const [tab, setTab] = useState<'write' | 'preview'>('write');
  const id = useId();
  const trimmed = body.trim();
  const tooLong = body.length > MAX;
  return (
    <form
      className="overflow-hidden rounded-md border border-border bg-surface focus-within:border-accent/70"
      onSubmit={(e) => {
        e.preventDefault();
        if (!trimmed || tooLong) return;
        onSubmit(trimmed, () => {
          setBody('');
          setTab('write');
        });
      }}
    >
      <div className="flex items-center gap-1 border-b border-border px-2 pt-1.5" role="tablist" aria-label="Composer mode">
        {(['write', 'preview'] as const).map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            aria-selected={tab === t}
            onClick={() => setTab(t)}
            className={cn('-mb-px border-b-2 px-2.5 pb-1.5 text-xs font-medium capitalize', tab === t ? 'border-accent text-text' : 'border-transparent text-muted hover:text-text')}
          >
            {t}
          </button>
        ))}
      </div>
      {tab === 'write' ? (
        <>
          <label htmlFor={id} className="sr-only">{submitLabel}</label>
          <Textarea
            id={id}
            // eslint-disable-next-line jsx-a11y/no-autofocus -- only set by callers that open the composer on an explicit Reply/Edit click
            autoFocus={autoFocus}
            value={body}
            onChange={(e) => setBody(e.target.value)}
            placeholder={placeholder}
            className="min-h-[96px] rounded-none border-0 bg-transparent focus:ring-0"
            onKeyDown={(e) => {
              if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') e.currentTarget.form?.requestSubmit();
            }}
          />
        </>
      ) : (
        <div className="min-h-[96px] px-3 py-2">{trimmed ? <Markdown source={body} /> : <p className="text-sm text-faint">Nothing to preview.</p>}</div>
      )}
      <div className="flex items-center justify-between gap-2 border-t border-border bg-surface-2/40 px-2 py-1.5">
        <span className={cn('text-[11px]', tooLong ? 'text-danger' : 'text-faint')}>
          {tooLong ? `${body.length - MAX} characters over the limit` : 'Ctrl+Enter to submit'}
        </span>
        <div className="flex gap-1.5">
          {onCancel && <Button variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>}
          <Button type="submit" size="sm" disabled={!trimmed || tooLong} loading={loading}>{submitLabel}</Button>
        </div>
      </div>
    </form>
  );
}
