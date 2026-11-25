import { useId, useRef, useState } from 'react';
import { Button } from '@/components/ui/Button';
import { EmojiButton, insertAtCaret } from '@/components/ui/EmojiButton';
import { Textarea } from '@/components/ui/Field';
import { cn } from '@/utils/format';

const MAX = 10_000;

/** Comment box: write, then post. Markdown and @mentions still work. */
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
  const id = useId();
  const box = useRef<HTMLTextAreaElement>(null);
  const trimmed = body.trim();
  const tooLong = body.length > MAX;
  return (
    <form
      className="overflow-hidden rounded-md border border-border bg-surface focus-within:border-accent/70"
      onSubmit={(e) => {
        e.preventDefault();
        if (!trimmed || tooLong) return;
        onSubmit(trimmed, () => setBody(''));
      }}
    >
      <label htmlFor={id} className="sr-only">{submitLabel}</label>
      <Textarea
        ref={box}
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
      <div className="flex items-center justify-between gap-2 border-t border-border bg-surface-2/40 px-2 py-1.5">
        <span className="flex items-center gap-1">
          <EmojiButton onPick={(emoji) => setBody((b) => insertAtCaret(box.current, b, emoji))} />
          <span className={cn('text-[11px]', tooLong ? 'text-danger' : 'text-faint')}>
          {tooLong ? `${body.length - MAX} characters over the limit` : 'Ctrl+Enter to submit'}
          </span>
        </span>
        <div className="flex gap-1.5">
          {onCancel && <Button variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>}
          <Button type="submit" size="sm" disabled={!trimmed || tooLong} loading={loading}>{submitLabel}</Button>
        </div>
      </div>
    </form>
  );
}
