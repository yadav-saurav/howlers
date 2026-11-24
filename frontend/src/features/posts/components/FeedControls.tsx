import { Check, ChevronDown, LayoutList, Rows3 } from 'lucide-react';
import type { ReactNode } from 'react';
import { Menu, MenuContent, MenuLabel, MenuTrigger } from '@/components/ui/Menu';
import * as DM from '@radix-ui/react-dropdown-menu';
import { useUiPrefs, type ViewMode } from '@/store/uiPrefs';
import { cn } from '@/utils/format';

export interface SortOption<T extends string> {
  value: T;
  label: string;
  icon?: ReactNode;
}

const triggerCls =
  'inline-flex h-8 items-center gap-1 rounded-full px-3 text-xs font-semibold text-muted transition-colors hover:bg-surface-2 hover:text-text data-[state=open]:bg-surface-2 data-[state=open]:text-text';

/** "Best ▾"-style dropdown. Keyboard accessible via Radix; the selected value is shown in the trigger. */
export function SortMenu<T extends string>({
  value,
  options,
  onChange,
  title,
}: {
  value: T;
  options: SortOption<T>[];
  onChange: (v: T) => void;
  title: string;
}) {
  const current = options.find((o) => o.value === value) ?? options[0];
  return (
    <Menu>
      <MenuTrigger className={triggerCls} aria-label={`${title}: ${current.label}`}>
        {current.icon}
        {current.label}
        <ChevronDown size={14} />
      </MenuTrigger>
      <MenuContent align="start">
        <MenuLabel>{title}</MenuLabel>
        {options.map((o) => (
          <DM.Item
            key={o.value}
            onSelect={() => onChange(o.value)}
            className="flex cursor-pointer select-none items-center gap-2.5 rounded-md px-2.5 py-2 text-sm outline-none data-[highlighted]:bg-surface-2"
          >
            <span className="text-muted [&>svg]:h-4 [&>svg]:w-4">{o.icon}</span>
            <span className="flex-1">{o.label}</span>
            {o.value === value && <Check size={15} className="text-accent" />}
          </DM.Item>
        ))}
      </MenuContent>
    </Menu>
  );
}

const VIEWS: SortOption<ViewMode>[] = [
  { value: 'card', label: 'Card', icon: <Rows3 size={15} /> },
  { value: 'compact', label: 'Compact', icon: <LayoutList size={15} /> },
];

export function ViewToggle() {
  const mode = useUiPrefs((s) => s.viewMode);
  const set = useUiPrefs((s) => s.setViewMode);
  return <SortMenu title="View" value={mode} options={VIEWS} onChange={set} />;
}

/** Row above a feed: sort, optional time range, view toggle. */
export function FeedControls({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn('flex items-center gap-1 px-2 pb-2 pt-3 sm:px-0', className)}>
      {children}
      <ViewToggle />
    </div>
  );
}
