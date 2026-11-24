import type { ReactNode } from 'react';
import { BrandMark } from '@/design-system/BrandMark';

export function AuthCard({ title, subtitle, children, footer }: { title: string; subtitle?: ReactNode; children: ReactNode; footer?: ReactNode }) {
  return (
    <div className="flex min-h-[calc(100vh-56px-180px)] items-start justify-center px-4 py-10 sm:items-center">
      <div className="w-full max-w-[400px]">
        <div className="mb-6 flex flex-col items-center text-center">
          <BrandMark size={36} />
          <h1 className="mt-4 font-serif text-2xl font-semibold tracking-tight">{title}</h1>
          {subtitle && <p className="mt-1.5 text-sm text-muted">{subtitle}</p>}
        </div>
        <div className="rounded-lg border border-border bg-surface p-5 sm:p-6">{children}</div>
        {footer && <div className="mt-5 text-center text-sm text-muted">{footer}</div>}
      </div>
    </div>
  );
}
