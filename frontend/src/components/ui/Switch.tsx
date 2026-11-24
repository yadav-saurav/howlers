import * as RS from '@radix-ui/react-switch';
import { useId } from 'react';

export function Switch({
  checked,
  onCheckedChange,
  label,
  description,
  disabled,
}: {
  checked: boolean;
  onCheckedChange: (v: boolean) => void;
  label: string;
  description?: string;
  disabled?: boolean;
}) {
  const id = useId();
  return (
    <div className="flex items-start justify-between gap-6 py-3">
      <label htmlFor={id} className="cursor-pointer">
        <span className="block text-sm font-medium">{label}</span>
        {description && <span className="mt-0.5 block text-xs text-muted">{description}</span>}
      </label>
      <RS.Root
        id={id}
        checked={checked}
        disabled={disabled}
        onCheckedChange={onCheckedChange}
        className="relative mt-0.5 h-6 w-10 shrink-0 rounded-full bg-border transition-colors data-[state=checked]:bg-accent disabled:opacity-50"
      >
        <RS.Thumb className="block h-5 w-5 translate-x-0.5 rounded-full bg-white shadow transition-transform data-[state=checked]:translate-x-[18px]" />
      </RS.Root>
    </div>
  );
}
