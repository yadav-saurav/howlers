import { ArrowBigDown, ArrowBigUp } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/features/auth/hooks';
import type { VoteValue } from '@/types/api';
import { cn, compactNumber } from '@/utils/format';
import { toggleTarget } from '../vote';

interface Props {
  score: number;
  myVote: VoteValue;
  onVote: (v: VoteValue) => void;
  /** pill: horizontal, filled capsule (cards, post page). vertical: rail (compact rows). horizontal: bare (comments). */
  orientation?: 'vertical' | 'horizontal' | 'pill';
  disabled?: boolean;
  size?: 'sm' | 'md';
  label: string;
}

/** Up/down vote control. Anonymous users are sent to log in instead of silently failing. */
export function VoteControl({ score, myVote, onVote, orientation = 'pill', disabled, size = 'md', label }: Props) {
  const { isAuthed } = useAuth();
  const navigate = useNavigate();
  const icon = size === 'sm' ? 17 : 19;
  const click = (dir: 1 | -1) => {
    if (!isAuthed) {
      navigate(`/login?next=${encodeURIComponent(window.location.pathname)}`);
      return;
    }
    onVote(toggleTarget(myVote, dir));
  };
  const pill = orientation === 'pill';
  const voted = myVote !== 0;
  return (
    <div
      role="group"
      aria-label={`Vote on ${label}`}
      className={cn(
        'flex items-center',
        orientation === 'vertical' ? 'flex-col gap-0.5' : 'gap-0.5',
        pill && 'h-8 rounded-full transition-colors',
        pill && !voted && 'bg-surface-2',
        pill && myVote === 1 && 'bg-accent text-accent-fg',
        pill && myVote === -1 && 'bg-down text-white',
      )}
    >
      <button
        type="button"
        aria-label="Upvote"
        aria-pressed={myVote === 1}
        disabled={disabled}
        onClick={() => click(1)}
        className={cn(
          'rounded-full p-1 transition-colors disabled:opacity-40',
          pill
            ? voted ? 'hover:bg-black/15' : 'text-muted hover:bg-surface-3 hover:text-accent'
            : myVote === 1 ? 'text-accent' : 'text-faint hover:bg-accent/10 hover:text-accent',
        )}
      >
        <ArrowBigUp size={icon} fill={myVote === 1 ? 'currentColor' : 'none'} strokeWidth={1.6} />
      </button>
      <span
        aria-live="polite"
        className={cn(
          'min-w-[2ch] text-center text-[13px] font-semibold tabular-nums',
          !pill && myVote === 1 && 'text-accent',
          !pill && myVote === -1 && 'text-down',
        )}
      >
        {compactNumber(score)}
      </span>
      <button
        type="button"
        aria-label="Downvote"
        aria-pressed={myVote === -1}
        disabled={disabled}
        onClick={() => click(-1)}
        className={cn(
          'rounded-full p-1 transition-colors disabled:opacity-40',
          pill
            ? voted ? 'hover:bg-black/15' : 'text-muted hover:bg-surface-3 hover:text-down'
            : myVote === -1 ? 'text-down' : 'text-faint hover:bg-down/10 hover:text-down',
        )}
      >
        <ArrowBigDown size={icon} fill={myVote === -1 ? 'currentColor' : 'none'} strokeWidth={1.6} />
      </button>
    </div>
  );
}
