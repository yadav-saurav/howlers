import { Check } from 'lucide-react';
import { Dialog } from '@/components/ui/Dialog';
import type { ChatThemeId } from '@/types/messages';
import { cn } from '@/utils/format';
import { CHAT_THEMES, themeVars } from '../themes';

/** Instagram-style theme picker: a live mini preview of each theme's wallpaper, bubbles and accent. */
export function ThemePicker({
  open,
  onOpenChange,
  current,
  onPick,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  current: ChatThemeId;
  onPick: (id: ChatThemeId) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Chat theme" description="Everyone in this conversation sees the theme you pick." size="lg">
      <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4" aria-label="Themes">
        {CHAT_THEMES.map((t) => {
          const selected = t.id === current;
          return (
            <li key={t.id}>
              <button
                type="button"
                aria-pressed={selected}
                onClick={() => onPick(t.id)}
                className={cn(
                  'group w-full overflow-hidden rounded-xl border text-left transition-shadow focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand',
                  selected ? 'border-transparent ring-2 ring-brand' : 'border-border hover:shadow-float',
                )}
              >
                <span className="chat-theme chat-wallpaper relative block h-24 px-2.5 py-2" style={themeVars(t)} aria-hidden="true">
                  <span className="chat-bubble-in block w-3/5 rounded-xl rounded-tl-sm px-2 py-1 text-[10px] leading-tight">Hey!</span>
                  <span className="chat-bubble-out ml-auto mt-1.5 block w-2/3 rounded-xl rounded-tr-sm px-2 py-1 text-right text-[10px] leading-tight">Love this vibe</span>
                  <span className="chat-accent absolute bottom-2 right-2.5 h-4 w-4 rounded-full" />
                  {selected && (
                    <span className="absolute left-2 top-[3.75rem] inline-flex h-5 w-5 items-center justify-center rounded-full bg-brand text-brand-fg">
                      <Check size={13} />
                    </span>
                  )}
                </span>
                <span className="block bg-surface px-2.5 py-1.5">
                  <span className="block text-sm font-semibold text-text">{t.name}</span>
                  <span className="block truncate text-xs text-muted">{t.vibe}</span>
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </Dialog>
  );
}
