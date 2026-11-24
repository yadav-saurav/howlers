import * as RD from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Outlet, ScrollRestoration, useLocation } from 'react-router-dom';
import { BrandMark } from '@/design-system/BrandMark';
import { DemoNotice } from '@/features/demo/DemoNotice';
import { SearchBox } from '@/features/search/components/SearchBox';
import { LeftNav } from './LeftNav';
import { MobileTabBar } from './MobileTabBar';
import { MiniPlayer } from '@/features/podcasts/components/MiniPlayer';
import { usePlayer } from '@/store/player';
import { cn } from '@/utils/format';
import { TopBar } from './TopBar';

/**
 * These dialogs are opened from TopBar buttons rather than Radix triggers, so Radix has nothing to return focus to on
 * close and focus fell back to <body>. Remember the element that had focus when the dialog opened and restore it.
 */
function useReturnFocus(open: boolean) {
  const opener = useRef<HTMLElement | null>(null);
  if (open && !opener.current && typeof document !== 'undefined') {
    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  }
  return (e: Event) => {
    const el = opener.current;
    opener.current = null;
    if (el && el.isConnected) {
      e.preventDefault();
      el.focus();
    }
  };
}

function Drawer({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const onCloseAutoFocus = useReturnFocus(open);
  return (
    <RD.Root open={open} onOpenChange={onOpenChange}>
      <RD.Portal>
        <RD.Overlay className="fixed inset-0 z-40 bg-black/50 md:hidden" />
        <RD.Content onCloseAutoFocus={onCloseAutoFocus} className="fixed inset-y-0 left-0 z-50 w-[280px] max-w-[85vw] overflow-y-auto border-r border-border bg-surface md:hidden">
          <div className="flex h-14 items-center justify-between border-b border-border px-4">
            <RD.Title asChild>
              <span><BrandMark withName /></span>
            </RD.Title>
            <RD.Description className="sr-only">Site navigation</RD.Description>
            <RD.Close aria-label="Close navigation" className="rounded-md p-1.5 text-muted hover:bg-surface-2">
              <X size={18} />
            </RD.Close>
          </div>
          <LeftNav onNavigate={() => onOpenChange(false)} />
        </RD.Content>
      </RD.Portal>
    </RD.Root>
  );
}

function MobileSearch({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const onCloseAutoFocus = useReturnFocus(open);
  return (
    <RD.Root open={open} onOpenChange={onOpenChange}>
      <RD.Portal>
        <RD.Overlay className="fixed inset-0 z-40 bg-black/50" />
        <RD.Content onCloseAutoFocus={onCloseAutoFocus} className="fixed inset-x-0 top-0 z-50 border-b border-border bg-surface p-3">
          <RD.Title className="sr-only">Search</RD.Title>
          <RD.Description className="sr-only">Search communities, posts and people</RD.Description>
          <SearchBox onDone={() => onOpenChange(false)} />
        </RD.Content>
      </RD.Portal>
    </RD.Root>
  );
}

export function AppLayout() {
  const [drawer, setDrawer] = useState(false);
  const [search, setSearch] = useState(false);
  const { pathname } = useLocation();
  useEffect(() => {
    setDrawer(false);
    setSearch(false);
  }, [pathname]);

  const hasPlayer = usePlayer((s) => s.current != null);

  return (
    <div className="min-h-screen">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-3 focus:z-50 focus:rounded-md focus:bg-surface focus:px-3 focus:py-2">
        Skip to content
      </a>
      <TopBar onOpenDrawer={() => setDrawer(true)} onOpenSearch={() => setSearch(true)} />
      <DemoNotice />
      <div className="flex">
        <aside className="sticky top-14 hidden h-[calc(100vh-56px)] w-16 shrink-0 overflow-y-auto border-r border-border [scrollbar-width:thin] md:block xl:w-[272px]">
          <div className="xl:hidden"><LeftNav compact /></div>
          <div className="hidden xl:block"><LeftNav /></div>
        </aside>
        <main id="main" tabIndex={-1} className={cn('min-w-0 flex-1 focus:outline-none', hasPlayer ? 'pb-40 md:pb-24' : 'pb-20 md:pb-10')}>
          <Outlet />
        </main>
      </div>
      <MiniPlayer />
      <MobileTabBar />
      <Drawer open={drawer} onOpenChange={setDrawer} />
      <MobileSearch open={search} onOpenChange={setSearch} />
      <ScrollRestoration />
    </div>
  );
}
