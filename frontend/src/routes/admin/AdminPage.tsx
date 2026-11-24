import { ShieldAlert, ShieldHalf } from 'lucide-react';
import { Link, useSearchParams } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/States';
import { useAuth } from '@/features/auth/hooks';
import { AuditTab } from '@/features/admin/components/AuditTab';
import { CommunitiesTab } from '@/features/admin/components/CommunitiesTab';
import { FlagsTab } from '@/features/admin/components/FlagsTab';
import { OverviewTab } from '@/features/admin/components/OverviewTab';
import { PaymentsTab } from '@/features/admin/components/PaymentsTab';
import { UsersTab } from '@/features/admin/components/UsersTab';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { cn } from '@/utils/format';

type Tab = 'overview' | 'users' | 'communities' | 'flags' | 'audit' | 'payments';

const TABS: { v: Tab; label: string }[] = [
  { v: 'overview', label: 'Overview' },
  { v: 'users', label: 'Users' },
  { v: 'communities', label: 'Communities' },
  { v: 'flags', label: 'Flags' },
  { v: 'audit', label: 'Audit log' },
  { v: 'payments', label: 'Payments' },
];

/** Platform admin dashboard. The server enforces the ADMIN role on every call; this check only avoids a dead page. */
export default function AdminPage() {
  useDocumentTitle('Admin');
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const tab = (TABS.find((t) => t.v === params.get('tab'))?.v ?? 'overview') as Tab;
  const go = (v: Tab) => setParams({ tab: v }, { replace: true });

  if (user?.role !== 'ADMIN') {
    return (
      <div className="mx-auto max-w-3xl px-4 py-10">
        <EmptyState icon={ShieldAlert} title="Admins only" description="This dashboard is for platform administrators."
          action={<Link to="/home" className={buttonClasses('primary', 'sm')}>Back to home</Link>} />
      </div>
    );
  }

  return (
    <div className="mx-auto w-full max-w-5xl px-3 py-4 sm:px-6">
      <div className="mb-4 flex items-center gap-3">
        <span className="inline-flex h-9 w-9 items-center justify-center rounded-full bg-surface-2 text-brand"><ShieldHalf size={18} /></span>
        <div>
          <h1 className="text-2xl font-bold">Admin</h1>
          <p className="text-sm text-muted">Site-wide accounts, communities and flags. Every action is recorded in the audit log.</p>
        </div>
      </div>
      <div role="tablist" aria-label="Admin sections" className="mb-4 flex gap-1 overflow-x-auto [scrollbar-width:none]">
        {TABS.map((t) => (
          <button key={t.v} role="tab" type="button" aria-selected={tab === t.v} onClick={() => go(t.v)}
            className={cn('inline-flex h-9 shrink-0 items-center rounded-full px-4 text-sm font-semibold', tab === t.v ? 'bg-surface-3 text-text' : 'text-muted hover:bg-surface-2')}>
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" aria-label={TABS.find((t) => t.v === tab)?.label}>
        {tab === 'overview' && <OverviewTab go={go} />}
        {tab === 'users' && <UsersTab />}
        {tab === 'communities' && <CommunitiesTab />}
        {tab === 'flags' && <FlagsTab />}
        {tab === 'audit' && <AuditTab />}
        {tab === 'payments' && <PaymentsTab />}
      </div>
    </div>
  );
}
