import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { CheckCircle2, AlertCircle, Info, X } from 'lucide-react';
import { create } from 'zustand';
import { cn } from '@/utils/format';

type ToastKind = 'success' | 'error' | 'info';
interface ToastItem {
  id: number;
  kind: ToastKind;
  message: string;
}

interface ToastState {
  toasts: ToastItem[];
  push: (kind: ToastKind, message: string) => void;
  dismiss: (id: number) => void;
}

let seq = 0;
export const useToastStore = create<ToastState>((set, get) => ({
  toasts: [],
  push: (kind, message) => {
    const id = ++seq;
    set({ toasts: [...get().toasts.slice(-3), { id, kind, message }] });
    window.setTimeout(() => get().dismiss(id), kind === 'error' ? 6000 : 3500);
  },
  dismiss: (id) => set({ toasts: get().toasts.filter((t) => t.id !== id) }),
}));

export const toast = {
  success: (m: string) => useToastStore.getState().push('success', m),
  error: (m: string) => useToastStore.getState().push('error', m),
  info: (m: string) => useToastStore.getState().push('info', m),
};

const icons = { success: CheckCircle2, error: AlertCircle, info: Info };

export function Toaster() {
  const toasts = useToastStore((s) => s.toasts);
  const dismiss = useToastStore((s) => s.dismiss);
  const reduce = useReducedMotion();
  return (
    <div
      aria-live="polite"
      role="status"
      className="pointer-events-none fixed bottom-20 left-1/2 z-[60] flex w-[calc(100vw-32px)] max-w-sm -translate-x-1/2 flex-col gap-2 md:bottom-6 md:left-auto md:right-6 md:translate-x-0"
    >
      <AnimatePresence initial={false}>
        {toasts.map((t) => {
          const Icon = icons[t.kind];
          return (
            <motion.div
              key={t.id}
              initial={reduce ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={reduce ? { opacity: 0 } : { opacity: 0, y: 8 }}
              transition={{ duration: 0.16 }}
              className="pointer-events-auto flex items-start gap-3 rounded-lg border border-border bg-surface px-3.5 py-3 text-sm shadow-float"
            >
              <Icon
                size={18}
                className={cn(
                  'mt-px shrink-0',
                  t.kind === 'success' && 'text-accent',
                  t.kind === 'error' && 'text-danger',
                  t.kind === 'info' && 'text-info',
                )}
              />
              <span className="flex-1">{t.message}</span>
              <button aria-label="Dismiss" onClick={() => dismiss(t.id)} className="text-faint hover:text-text">
                <X size={16} />
              </button>
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>
  );
}
