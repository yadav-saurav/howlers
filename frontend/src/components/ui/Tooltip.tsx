import * as RT from '@radix-ui/react-tooltip';
import type { ReactNode } from 'react';

export const TooltipProvider = RT.Provider;

export function Tooltip({ content, children }: { content: ReactNode; children: ReactNode }) {
  return (
    <RT.Root delayDuration={350}>
      <RT.Trigger asChild>{children}</RT.Trigger>
      <RT.Portal>
        <RT.Content
          sideOffset={6}
          className="z-50 rounded-md border border-border bg-surface px-2 py-1 text-xs text-text shadow-float"
        >
          {content}
        </RT.Content>
      </RT.Portal>
    </RT.Root>
  );
}
