import { ChevronDown, Plus } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link, NavLink, useLocation } from 'react-router-dom';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { useAuth } from '@/features/auth/hooks';
import { useMyCommunities } from '@/features/communities/hooks';
import { useUiPrefs } from '@/store/uiPrefs';
import { cn } from '@/utils/format';
import { primaryNav, upcomingNav, type NavItem } from './navItems';

function isActive(item: NavItem, pathname: string, search: string): boolean {
  const [path, query] = item.to.split('?');
  if (query) return pathname === path && search.includes(query);
  if (path === '/home') return pathname === '/home' && !search.includes('tab=');
  return pathname === path || pathname.startsWith(`${path}/`);
}

const rowCls = (active: boolean, compact?: boolean) =>
  cn(
    'flex h-10 items-center gap-3 rounded-lg px-4 text-sm transition-colors',
    active ? 'bg-surface-2 font-medium text-text' : 'text-text/85 hover:bg-surface-2/70',
    compact && 'justify-center px-0',
  );

function NavRow({ item, compact, onNavigate }: { item: NavItem; compact?: boolean; onNavigate?: () => void }) {
  const { pathname, search } = useLocation();
  const active = isActive(item, pathname, search);
  const Icon = item.icon;
  return (
    <Link to={item.to} onClick={onNavigate} aria-current={active ? 'page' : undefined} title={compact ? item.label : undefined} className={rowCls(active, compact)}>
      <Icon size={20} strokeWidth={active ? 2.2 : 1.8} />
      {compact ? <span className="sr-only">{item.label}</span> : item.label}
    </Link>
  );
}

/** Collapsible nav section; open/closed state is remembered per device. */
function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  const collapsed = useUiPrefs((s) => s.collapsed.includes(id));
  const toggle = useUiPrefs((s) => s.toggleSection);
  return (
    <div className="border-t border-border py-3">
      <button
        type="button"
        onClick={() => toggle(id)}
        aria-expanded={!collapsed}
        className="flex h-9 w-full items-center justify-between rounded-lg px-4 text-xs font-semibold uppercase tracking-wider text-muted hover:bg-surface-2/70"
      >
        {title}
        <ChevronDown size={16} className={cn('transition-transform', collapsed && '-rotate-90')} />
      </button>
      {!collapsed && <div className="mt-1">{children}</div>}
    </div>
  );
}

function CommunityLink({ slug, iconUrl, onNavigate }: { slug: string; iconUrl: string | null; onNavigate?: () => void }) {
  return (
    <NavLink to={`/c/${slug}`} onClick={onNavigate} className={({ isActive: a }) => rowCls(a)}>
      <Avatar src={iconUrl} name={slug} size="sm" />
      <span className="truncate">c/{slug}</span>
    </NavLink>
  );
}

export function LeftNav({ compact, onNavigate }: { compact?: boolean; onNavigate?: () => void }) {
  const { isAuthed } = useAuth();
  const mine = useMyCommunities();
  const recent = useUiPrefs((s) => s.recentCommunities);

  if (compact) {
    return (
      <nav aria-label="Main" className="flex flex-col gap-1 px-2 py-3">
        {primaryNav.filter((i) => !i.auth || isAuthed).map((i) => <NavRow key={i.to} item={i} compact onNavigate={onNavigate} />)}
        {upcomingNav.length > 0 && <div className="my-2 h-px bg-border" />}
        {upcomingNav.map((i) => <NavRow key={i.to} item={i} compact onNavigate={onNavigate} />)}
      </nav>
    );
  }

  return (
    <nav aria-label="Main" className="flex flex-col px-3 py-3">
      <div className="pb-3">
        {primaryNav.filter((i) => !i.auth || isAuthed).map((i) => <NavRow key={i.to} item={i} onNavigate={onNavigate} />)}
      </div>

      {recent.length > 0 && (
        <Section id="recent" title="Recent">
          {recent.map((c) => <CommunityLink key={c.slug} slug={c.slug} iconUrl={c.iconUrl} onNavigate={onNavigate} />)}
        </Section>
      )}

      {isAuthed && (
        <Section id="communities" title="Communities">
          <Link to="/communities/new" onClick={onNavigate} className={rowCls(false)}>
            <Plus size={20} strokeWidth={1.8} /> Create a community
          </Link>
          {mine.isLoading && Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="mx-4 my-2.5 h-5" />)}
          {mine.data?.map((c) => <CommunityLink key={c.id} slug={c.slug} iconUrl={c.iconUrl} onNavigate={onNavigate} />)}
          {mine.data && mine.data.length === 0 && <p className="px-4 py-1 text-xs text-faint">Join communities to see them here.</p>}
        </Section>
      )}

      {upcomingNav.length > 0 && (
        <Section id="resources" title="Coming next">
          {upcomingNav.map((i) => <NavRow key={i.to} item={i} onNavigate={onNavigate} />)}
        </Section>
      )}

      <div className="flex flex-wrap gap-x-3 gap-y-1 border-t border-border px-4 pt-4 text-xs text-faint">
        <Link to="/about" className="hover:text-muted">About</Link>
        <Link to="/terms" className="hover:text-muted">Terms</Link>
        <Link to="/privacy" className="hover:text-muted">Privacy</Link>
      </div>
    </nav>
  );
}
