import * as RT from '@radix-ui/react-tabs';
import type { ReactNode } from 'react';
import { cn } from '@/utils/format';

export const Tabs = RT.Root;
export const TabPanel = RT.Content;

export function TabList({ children, className, label }: { children: ReactNode; className?: string; label: string }) {
  return (
    <RT.List
      aria-label={label}
      className={cn('flex gap-1 overflow-x-auto border-b border-border [scrollbar-width:none]', className)}
    >
      {children}
    </RT.List>
  );
}

export function Tab({ value, children }: { value: string; children: ReactNode }) {
  return (
    <RT.Trigger
      value={value}
      className={cn(
        'relative -mb-px whitespace-nowrap border-b-2 border-transparent px-3 py-2.5 text-sm font-medium text-muted',
        'hover:text-text data-[state=active]:border-accent data-[state=active]:text-text',
      )}
    >
      {children}
    </RT.Trigger>
  );
}

/** Link/button-driven segmented tabs for URL-controlled views (feeds, sorts). */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
  className,
}: {
  value: T;
  options: { value: T; label: string; icon?: ReactNode }[];
  onChange: (v: T) => void;
  label: string;
  className?: string;
}) {
  return (
    <div role="tablist" aria-label={label} className={cn('flex gap-1 overflow-x-auto [scrollbar-width:none]', className)}>
      {options.map((o) => (
        <button
          key={o.value}
          role="tab"
          type="button"
          aria-selected={o.value === value}
          onClick={() => onChange(o.value)}
          className={cn(
            'inline-flex h-8 items-center gap-1.5 whitespace-nowrap rounded-md px-3 text-sm font-medium transition-colors',
            o.value === value ? 'bg-surface-2 text-text' : 'text-muted hover:text-text hover:bg-surface-2/60',
          )}
        >
          {o.icon}
          {o.label}
        </button>
      ))}
    </div>
  );
}
