import { Library } from 'lucide-react';
import { useParams } from 'react-router-dom';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { LibraryItem } from '@/features/collections/components/LibraryItem';
import { useSharedCollection } from '@/features/collections/api';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { ApiError } from '@/services/apiError';

/** Read-only view of a collection someone shared by link. Private-community items are filtered server-side. */
export default function SharedCollectionPage() {
  const { token = '' } = useParams();
  const q = useSharedCollection(token);
  useDocumentTitle(q.data?.collection.name ?? 'Shared collection');
  return (
    <div className="mx-auto w-full max-w-3xl px-3 py-6 sm:px-6">
      {q.isPending && <div className="space-y-3"><Skeleton className="h-10 w-1/2" /><Skeleton className="h-28" /><Skeleton className="h-28" /></div>}
      {q.isError && (q.error instanceof ApiError && q.error.status === 404
        ? <EmptyState icon={Library} title="This collection isn't available" description="The owner may have made it private, which revokes the link." />
        : <ErrorState error={q.error} onRetry={() => q.refetch()} />)}
      {q.data && (
        <>
          <div className="mb-5">
            <div className="text-xs font-semibold uppercase tracking-wider text-muted">Shared collection</div>
            <h1 className="mt-1 text-2xl font-bold">{q.data.collection.name}</h1>
            {q.data.collection.description && <p className="mt-1 text-sm text-muted">{q.data.collection.description}</p>}
            {q.data.owner && (
              <p className="mt-2 flex items-center gap-2 text-sm text-muted">
                <Avatar src={q.data.owner.avatarUrl} name={q.data.owner.username} size="xs" /> curated by u/{q.data.owner.username}
              </p>
            )}
          </div>
          {q.data.items.length === 0 ? (
            <EmptyState icon={Library} title="Nothing to show" description="This collection is empty or only contains posts you can't access." />
          ) : (
            <div className="space-y-3">
              {q.data.items.map((it) => <LibraryItem key={it.id} item={it} layout="list" collections={[]} readOnly />)}
            </div>
          )}
        </>
      )}
    </div>
  );
}
