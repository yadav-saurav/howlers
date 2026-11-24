import * as DM from '@radix-ui/react-dropdown-menu';
import type { ReactNode } from 'react';
import { cn } from '@/utils/format';

export const Menu = DM.Root;
export const MenuTrigger = DM.Trigger;

export function MenuContent({ children, align = 'end' }: { children: ReactNode; align?: 'start' | 'end' | 'center' }) {
  return (
    <DM.Portal>
      <DM.Content
        align={align}
        sideOffset={6}
        className="z-50 min-w-[200px] rounded-lg border border-border bg-surface p-1 shadow-float"
      >
        {children}
      </DM.Content>
    </DM.Portal>
  );
}

export function MenuItem({
  children,
  onSelect,
  icon,
  danger,
  disabled,
}: {
  children: ReactNode;
  onSelect?: () => void;
  icon?: ReactNode;
  danger?: boolean;
  disabled?: boolean;
}) {
  return (
    <DM.Item
      disabled={disabled}
      onSelect={onSelect}
      className={cn(
        'flex cursor-pointer select-none items-center gap-2.5 rounded-md px-2.5 py-2 text-sm outline-none',
        'data-[highlighted]:bg-surface-2 data-[disabled]:opacity-50 data-[disabled]:pointer-events-none',
        danger ? 'text-danger' : 'text-text',
      )}
    >
      {icon && <span className="text-muted [&>svg]:h-4 [&>svg]:w-4">{icon}</span>}
      {children}
    </DM.Item>
  );
}

export function MenuSeparator() {
  return <DM.Separator className="my-1 h-px bg-border" />;
}

export function MenuLabel({ children }: { children: ReactNode }) {
  return <DM.Label className="px-2.5 py-1.5 text-xs text-faint">{children}</DM.Label>;
}
