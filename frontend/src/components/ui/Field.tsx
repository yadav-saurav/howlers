import {
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import { cn } from '@/utils/format';

const control =
  'w-full rounded-md border border-border bg-surface-2 px-3 text-[14px] text-text placeholder:text-faint ' +
  'transition-colors focus:outline-none focus:border-accent focus:ring-2 focus:ring-accent/25 ' +
  'disabled:opacity-60 aria-[invalid=true]:border-danger';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input(
  { className, ...rest },
  ref,
) {
  return <input ref={ref} className={cn(control, 'h-10', className)} {...rest} />;
});

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(
  function Textarea({ className, ...rest }, ref) {
    return <textarea ref={ref} className={cn(control, 'py-2 min-h-[96px] resize-y', className)} {...rest} />;
  },
);

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(function Select(
  { className, children, ...rest },
  ref,
) {
  return (
    <select ref={ref} className={cn(control, 'h-10 pr-8', className)} {...rest}>
      {children}
    </select>
  );
});

export function FieldError({ id, message }: { id?: string; message?: string }) {
  if (!message) return null;
  return (
    <p id={id} role="alert" className="mt-1.5 text-xs text-danger">
      {message}
    </p>
  );
}

interface FieldProps {
  label: string;
  error?: string;
  hint?: ReactNode;
  children: (props: { id: string; 'aria-invalid': boolean; 'aria-describedby'?: string }) => ReactNode;
  className?: string;
  optional?: boolean;
}

/** Label + control + hint + error with correct aria wiring. */
export function Field({ label, error, hint, children, className, optional }: FieldProps) {
  const id = useId();
  const errId = `${id}-err`;
  const hintId = `${id}-hint`;
  const describedBy = [error ? errId : null, hint ? hintId : null].filter(Boolean).join(' ') || undefined;
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 flex items-baseline gap-2 text-sm font-medium text-text">
        {label}
        {optional && <span className="text-xs font-normal text-faint">optional</span>}
      </label>
      {children({ id, 'aria-invalid': !!error, 'aria-describedby': describedBy })}
      {hint && !error && (
        <div id={hintId} className="mt-1.5 text-xs text-faint">
          {hint}
        </div>
      )}
      <FieldError id={errId} message={error} />
    </div>
  );
}

export function FormAlert({ message }: { message: string | null | undefined }) {
  if (!message) return null;
  return (
    <div role="alert" className="rounded-md border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger">
      {message}
    </div>
  );
}
