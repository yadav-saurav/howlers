import { ArrowLeft, FileSearch } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Display';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { usePoolReport } from '@/features/funding/api';
import { CheckoutReturn } from '@/features/funding/components/CheckoutReturn';
import { ModeBanner } from '@/features/funding/components/Mode';
import { PoolCard } from '@/features/funding/components/PoolCard';
import { TransparencyReportView } from '@/features/funding/components/TransparencyReportView';
import { useFundingStatus } from '@/features/funding/api';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns } from '@/layouts/PageColumns';
import { ApiError } from '@/services/apiError';

/** Public transparency report for one pool (one community, one period). */
export default function PoolReportPage() {
  const { id = '' } = useParams();
  const q = usePoolReport(Number(id));
  const status = useFundingStatus();
  useDocumentTitle(q.data ? `Transparency report · ${q.data.pool.title}` : 'Transparency report');

  return (
    <PageColumns>
      <div className="space-y-5 px-3 pt-2 sm:px-0">
        {q.data && (
          <Link to={`/c/${q.data.pool.community.slug}?view=funding`} className="inline-flex items-center gap-1 text-sm text-muted hover:text-text">
            <ArrowLeft size={15} aria-hidden="true" /> c/{q.data.pool.community.slug} funding
          </Link>
        )}
        <h1 className="text-2xl font-bold">Transparency report</h1>
        <CheckoutReturn />
        <ModeBanner status={status.data} />
        {q.isPending && <Skeleton className="h-64 rounded-2xl" />}
        {q.isError && (q.error instanceof ApiError && q.error.status === 404
          ? <EmptyState icon={FileSearch} title="Pool not found" description="It may be in a private community you can’t see." action={<Link to="/funding" className={buttonClasses('secondary', 'sm')}>Moderator funding</Link>} />
          : <ErrorState error={q.error} onRetry={() => q.refetch()} />)}
        {q.data && (
          <>
            <PoolCard pool={q.data.pool} showCommunity reportLink={false} />
            <TransparencyReportView r={q.data} />
          </>
        )}
      </div>
    </PageColumns>
  );
}
