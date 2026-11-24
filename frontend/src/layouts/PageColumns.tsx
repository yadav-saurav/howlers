import type { ReactNode } from 'react';
import { cn } from '@/utils/format';

/**
 * Feed column + optional right rail. The rail shows from lg up; below that,
 * pages may render `railMobile` content inline (design doc: rail modules move below the header).
 */
export function PageColumns({
  children,
  rail,
  className,
  wide,
}: {
  children: ReactNode;
  rail?: ReactNode;
  className?: string;
  wide?: boolean;
}) {
  return (
    <div className={cn('mx-auto flex w-full gap-6 px-0 py-0 sm:px-4 sm:py-3 lg:px-6', wide ? 'max-w-6xl' : 'max-w-[1120px]', className)}>
      <div className="min-w-0 flex-1">{children}</div>
      {rail && (
        <aside className="sticky top-[68px] hidden max-h-[calc(100vh-80px)] w-[316px] shrink-0 space-y-4 self-start overflow-y-auto pb-6 [scrollbar-width:none] lg:block">
          {rail}
        </aside>
      )}
    </div>
  );
}

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-3 px-4 pb-4 pt-5 sm:px-0 sm:pt-0">
      <div>
        <h1 className="font-serif text-2xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted">{description}</p>}
      </div>
      {actions}
    </div>
  );
}
