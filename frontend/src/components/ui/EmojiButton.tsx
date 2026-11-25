import { Smile } from 'lucide-react';
import { lazy, Suspense, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { cn } from '@/utils/format';

// The picker (every emoji, search, skin tones) is only downloaded the first time someone opens it.
const Picker = lazy(() => import('./EmojiPickerPanel'));

const PANEL_W = 336;
const PANEL_H = 420;

/**
 * Inserts `text` into a textarea or input at the caret (replacing any selection) and returns the new value.
 * The caret is put back right after the inserted text.
 */
export function insertAtCaret(el: HTMLTextAreaElement | HTMLInputElement | null, current: string, text: string): string {
  if (!el) return current + text;
  const start = el.selectionStart ?? current.length;
  const end = el.selectionEnd ?? current.length;
  const next = current.slice(0, start) + text + current.slice(end);
  const caret = start + text.length;
  requestAnimationFrame(() => {
    el.focus();
    el.setSelectionRange(caret, caret);
  });
  return next;
}

/**
 * A smiley button that opens an emoji picker next to it. Used by the comment box, the chat composer and the post
 * editor. Escape or a click outside closes it; picking an emoji calls `onPick` and keeps the picker open for more.
 */
export function EmojiButton({
  onPick,
  className,
  size = 18,
  label = 'Add emoji',
}: {
  onPick: (emoji: string) => void;
  className?: string;
  size?: number;
  label?: string;
}) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  const btn = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    if (!open || !btn.current) return;
    const place = () => {
      const r = btn.current!.getBoundingClientRect();
      const w = Math.min(PANEL_W, window.innerWidth - 16);
      const h = Math.min(PANEL_H, window.innerHeight - 16);
      const left = Math.min(Math.max(8, r.left), window.innerWidth - w - 8);
      // Open upwards when there's more room above (chat composers sit at the bottom of the screen).
      const above = r.top > window.innerHeight - r.bottom;
      const top = above ? Math.max(8, r.top - h - 8) : Math.min(window.innerHeight - h - 8, r.bottom + 8);
      setPos({ top, left });
    };
    place();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => {
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      const t = e.target as Node;
      if (!panel.current?.contains(t) && !btn.current?.contains(t)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        setOpen(false);
        btn.current?.focus();
      }
    };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey, true);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('keydown', onKey, true);
    };
  }, [open]);

  return (
    <>
      <button
        ref={btn}
        type="button"
        aria-label={label}
        title={label}
        aria-haspopup="dialog"
        aria-expanded={open}
        // Keep the text box's caret and selection while the button is pressed.
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => setOpen((o) => !o)}
        className={cn(
          'inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-muted transition-colors hover:bg-surface-2 hover:text-text',
          open && 'bg-surface-2 text-text',
          className,
        )}
      >
        <Smile size={size} />
      </button>
      {open && pos &&
        createPortal(
          <div
            ref={panel}
            role="dialog"
            aria-label="Emoji picker"
            className="fixed z-[70] overflow-hidden rounded-2xl border border-border bg-surface shadow-xl"
            style={{ top: pos.top, left: pos.left, width: Math.min(PANEL_W, window.innerWidth - 16) }}
          >
            <Suspense fallback={<div className="flex h-[420px] items-center justify-center text-sm text-muted">Loading emojis…</div>}>
              <Picker
                height={Math.min(PANEL_H, window.innerHeight - 16)}
                onPick={(emoji) => onPick(emoji)}
              />
            </Suspense>
          </div>,
          document.body,
        )}
    </>
  );
}
