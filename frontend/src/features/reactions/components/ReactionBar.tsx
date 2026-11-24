import { SmilePlus } from 'lucide-react';
import { useEffect, useId, useRef, useState } from 'react';
import { toast } from '@/components/ui/Toast';
import { Tooltip } from '@/components/ui/Tooltip';
import { errorMessage } from '@/services/apiError';
import { useAuthStore } from '@/store/authStore';
import type { ReactionTargetType } from '@/types/reactions';
import { cn } from '@/utils/format';
import { useReactions, useToggleReaction } from '../api';
import { REACTIONS, chipLabel, pickerKey, reactedByLabel } from '../logic';

const COLUMNS = 5;

/**
 * Picker popup: a labelled toolbar of ten emoji buttons with roving focus (arrows, Home, End), Escape to close (focus
 * returns to the trigger), and a click outside to dismiss. Each button says what it does ("React with Fire") and
 * whether it is already on.
 */
function Picker({ active, onPick, onClose, labelledBy, align }: {
  active: Set<string>;
  onPick: (key: string) => void;
  onClose: (refocus: boolean) => void;
  labelledBy: string;
  align: 'start' | 'end';
}) {
  const [focus, setFocus] = useState(0);
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    refs.current[focus]?.focus();
  }, [focus]);
  useEffect(() => {
    const away = (e: MouseEvent) => {
      // The wrapper also holds the trigger, whose own click toggles the picker.
      const wrapper = box.current?.parentElement;
      if (wrapper && !wrapper.contains(e.target as Node)) onClose(false);
    };
    document.addEventListener('mousedown', away);
    return () => document.removeEventListener('mousedown', away);
  }, [onClose]);
  return (
    <div
      ref={box}
      role="toolbar"
      aria-labelledby={labelledBy}
      className={cn('absolute bottom-full z-40 mb-1.5 grid w-max grid-cols-5', align === 'end' ? 'right-0' : 'left-0', 'gap-0.5 rounded-xl border border-border bg-surface p-1.5 shadow-float')}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          e.stopPropagation();
          onClose(true);
          return;
        }
        const next = pickerKey(e.key, focus, REACTIONS.length, COLUMNS);
        if (next != null) {
          e.preventDefault();
          setFocus(next);
        }
      }}
    >
      {REACTIONS.map((r, i) => (
        <button
          key={r.key}
          ref={(el) => {
            refs.current[i] = el;
          }}
          type="button"
          tabIndex={i === focus ? 0 : -1}
          aria-pressed={active.has(r.key)}
          aria-label={`React with ${r.label}`}
          title={r.label}
          onClick={() => onPick(r.key)}
          className={cn(
            'flex h-9 w-9 items-center justify-center rounded-lg text-xl transition-transform hover:scale-110 hover:bg-surface-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
            active.has(r.key) && 'bg-accent/15',
          )}
        >
          <span aria-hidden="true">{r.emoji}</span>
        </button>
      ))}
    </div>
  );
}

/**
 * Reaction chips plus an "add reaction" button for one post, comment or message (D-044). Counts load in batches; a
 * toggle is optimistic and the server's summary replaces it. Signed-out readers see the counts but can't react.
 */
export function ReactionBar({ type, id, disabled, compact, align = 'start', withPicker = true }: {
  type: ReactionTargetType;
  id: number;
  /** e.g. a locked or removed post: counts stay visible, no new reactions. */
  disabled?: boolean;
  compact?: boolean;
  align?: 'start' | 'end';
  /** false: chips only (the caller places an {@link AddReactionButton} elsewhere, e.g. beside a message). */
  withPicker?: boolean;
}) {
  const authed = useAuthStore((s) => s.status === 'authenticated');
  const myName = useAuthStore((s) => s.user?.username ?? null);
  const q = useReactions(type, id);
  const toggle = useToggleReaction(type, id);
  const reactions = q.data?.reactions ?? [];
  const canReact = authed && !disabled;

  const set = (key: string, on: boolean) => {
    toggle.mutate({ reaction: key, on }, { onError: (e) => toast.error(errorMessage(e)) });
  };

  if (reactions.length === 0 && !(canReact && withPicker)) return null;
  return (
    <div className={cn('relative flex flex-wrap items-center gap-1', align === 'end' && 'justify-end')}>
      {reactions.map((r) => {
        const label = chipLabel(r, myName);
        const chip = cn(
          'inline-flex items-center gap-1 rounded-full border px-2 tabular-nums transition-colors',
          compact ? 'h-6 text-xs' : 'h-7 text-[13px]',
          r.mine ? 'border-accent/60 bg-accent/15 text-text' : 'border-border bg-surface-2/60 text-muted',
        );
        return (
          <Tooltip key={r.reaction} content={reactedByLabel(r, myName) || r.label}>
            {canReact ? (
              <button type="button" aria-pressed={r.mine} aria-label={label} onClick={() => set(r.reaction, !r.mine)}
                className={cn(chip, 'hover:border-accent/60')}>
                <span aria-hidden="true">{r.emoji}</span>
                <span aria-hidden="true">{r.count}</span>
              </button>
            ) : (
              <span role="img" aria-label={label} className={chip}>
                <span aria-hidden="true">{r.emoji}</span>
                <span aria-hidden="true">{r.count}</span>
              </span>
            )}
          </Tooltip>
        );
      })}
      {canReact && withPicker && <AddReactionButton type={type} id={id} compact={compact} />}
    </div>
  );
}

/** The "add reaction" button and its picker. Shares the cached summary with any {@link ReactionBar} for the same target. */
export function AddReactionButton({ type, id, compact, className, popupAlign = 'start' }: {
  type: ReactionTargetType;
  id: number;
  compact?: boolean;
  className?: string;
  popupAlign?: 'start' | 'end';
}) {
  const authed = useAuthStore((s) => s.status === 'authenticated');
  const q = useReactions(type, id);
  const toggle = useToggleReaction(type, id);
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const labelId = useId();
  const reactions = q.data?.reactions ?? [];
  if (!authed) return null;

  const pick = (key: string) => {
    const current = reactions.find((r) => r.reaction === key);
    toggle.mutate({ reaction: key, on: !current?.mine }, { onError: (e) => toast.error(errorMessage(e)) });
    setOpen(false);
    trigger.current?.focus();
  };

  return (
    <span className={cn('relative', className)}>
      <span id={labelId} className="sr-only">Pick a reaction</span>
      <button
        ref={trigger}
        type="button"
        aria-label="Add a reaction"
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className={cn(
          'inline-flex items-center justify-center rounded-full text-muted hover:bg-surface-2 hover:text-text focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
          compact ? 'h-6 w-6' : 'h-7 w-7',
        )}
      >
        <SmilePlus size={compact ? 14 : 16} aria-hidden="true" />
      </button>
      {open && (
        <Picker
          active={new Set(reactions.filter((r) => r.mine).map((r) => r.reaction))}
          onPick={pick}
          labelledBy={labelId}
          align={popupAlign}
          onClose={(refocus) => {
            setOpen(false);
            if (refocus) trigger.current?.focus();
          }}
        />
      )}
    </span>
  );
}
