import * as RD from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { useLayoutEffect, useRef, type ReactNode } from 'react';
import { cn } from '@/utils/format';

interface DialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
  size?: 'sm' | 'md' | 'lg';
}

export function Dialog({ open, onOpenChange, title, description, children, footer, size = 'md' }: DialogProps) {
  // Our dialogs open from ordinary buttons and menu items, not RD.Trigger, so Radix has no trigger to return focus to
  // when they close. Remember what had focus when the dialog opened and give it back (WCAG 2.4.3 focus order).
  const returnTo = useRef<HTMLElement | null>(null);
  useLayoutEffect(() => {
    if (open && !returnTo.current) {
      const active = document.activeElement;
      returnTo.current = active instanceof HTMLElement && active !== document.body ? active : null;
    }
  }, [open]);
  return (
    <RD.Root open={open} onOpenChange={onOpenChange}>
      <RD.Portal>
        <RD.Overlay className="fixed inset-0 z-50 bg-black/55 data-[state=open]:animate-[fadeIn_120ms_ease-out]" />
        <RD.Content
          onCloseAutoFocus={(e) => {
            const el = returnTo.current;
            returnTo.current = null;
            if (el?.isConnected) {
              e.preventDefault();
              el.focus();
            }
          }}
          className={cn(
            'fixed left-1/2 top-1/2 z-50 w-[calc(100vw-32px)] -translate-x-1/2 -translate-y-1/2',
            'max-h-[85vh] overflow-y-auto rounded-lg border border-border bg-surface shadow-float',
            size === 'sm' && 'max-w-sm',
            size === 'md' && 'max-w-lg',
            size === 'lg' && 'max-w-2xl',
          )}
        >
          <div className="flex items-start justify-between gap-4 border-b border-border px-5 py-4">
            <div>
              <RD.Title className="font-serif text-lg font-semibold">{title}</RD.Title>
              {description ? (
                <RD.Description className="mt-1 text-sm text-muted">{description}</RD.Description>
              ) : (
                <RD.Description className="sr-only">{title}</RD.Description>
              )}
            </div>
            <RD.Close className="rounded-md p-1 text-muted hover:bg-surface-2 hover:text-text" aria-label="Close">
              <X size={18} />
            </RD.Close>
          </div>
          <div className="px-5 py-4">{children}</div>
          {footer && <div className="flex justify-end gap-2 border-t border-border px-5 py-3">{footer}</div>}
        </RD.Content>
      </RD.Portal>
    </RD.Root>
  );
}
