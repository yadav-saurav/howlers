import { AudioWaveform } from 'lucide-react';
import { cn } from '@/utils/format';
import { aiVoiceDescription } from '../audioGeneration';

/**
 * Persistent "AI-generated voice" label for episodes whose audio was synthesised from a script (D-036).
 * The full disclosure is in the title and the screen-reader text, so it's never hidden behind a hover alone.
 */
export function AiVoiceBadge({ provider, model, size = 'sm', className }: {
  provider?: string | null;
  model?: string | null;
  size?: 'xs' | 'sm';
  className?: string;
}) {
  const description = aiVoiceDescription(provider, model);
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1 rounded-full border border-warning/40 bg-warning/10 font-semibold text-warning',
        size === 'xs' ? 'px-1.5 py-px text-[10px]' : 'px-2 py-0.5 text-[11px]',
        className,
      )}
      title={description}
    >
      <AudioWaveform size={size === 'xs' ? 10 : 12} aria-hidden="true" />
      AI-generated voice
      <span className="sr-only">. {description}</span>
    </span>
  );
}
