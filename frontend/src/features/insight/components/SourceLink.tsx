import { CornerDownRight } from 'lucide-react';

/** Jumps from an extracted line/argument to the original comment in the thread. */
export function SourceLink({ commentId, author, onJump }: { commentId: number; author?: string | null; onJump: (id: number) => void }) {
  return (
    <button
      type="button"
      onClick={() => onJump(commentId)}
      className="inline-flex items-center gap-1 text-xs font-semibold text-brand hover:underline"
      aria-label={`Open source comment${author ? ` by ${author}` : ''}`}
    >
      <CornerDownRight size={12} /> {author ? `u/${author}` : 'source'}
    </button>
  );
}
