import type { ReactNode } from 'react';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';

export function StaticPage({ title, updated, children }: { title: string; updated?: string; children: ReactNode }) {
  useDocumentTitle(title);
  return (
    <article className="mx-auto max-w-2xl px-4 py-12 sm:py-16">
      <h1 className="font-serif text-3xl font-semibold tracking-tight">{title}</h1>
      {updated && <p className="mt-2 text-sm text-faint">Last updated {updated}</p>}
      <div className="prose-howlers mt-8">{children}</div>
    </article>
  );
}
