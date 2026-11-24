import { Copy, FolderPlus, LayoutGrid, Library, List, Lock, MoreHorizontal, Pencil, Search, Share2, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Skeleton } from '@/components/ui/Display';
import { Input, Textarea } from '@/components/ui/Field';
import { Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from '@/components/ui/Menu';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { collectionsApi, useCollectionItems, useCollectionMutation, useLibraryTags, useMyCollections } from '@/features/collections/api';
import { LibraryItem } from '@/features/collections/components/LibraryItem';
import { moveItem } from '@/features/collections/tags';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { errorMessage } from '@/services/apiError';
import type { Collection, CollectionItem } from '@/types/knowledge';
import { absoluteUrl, cn } from '@/utils/format';

const SUGGESTED = ['Read Later', 'Career Advice', 'System Design', 'Interview Preparation'];

function CollectionForm({ initial, onDone }: { initial?: Collection; onDone: () => void }) {
  const [name, setName] = useState(initial?.name ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  const save = useCollectionMutation((b: { name: string; description: string }) =>
    initial ? collectionsApi.update(initial.id, b) : collectionsApi.create(b),
  );
  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate({ name: name.trim(), description: description.trim() }, { onSuccess: () => { toast.success(initial ? 'Collection updated' : 'Collection created'); onDone(); }, onError: (er) => toast.error(errorMessage(er)) });
      }}
    >
      <label className="block text-sm font-semibold">Name<Input value={name} onChange={(e) => setName(e.target.value)} maxLength={60} required className="mt-1" /></label>
      <label className="block text-sm font-semibold">Description<Textarea value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} rows={3} className="mt-1" /></label>
      {!initial && (
        <div className="flex flex-wrap gap-1.5">
          {SUGGESTED.map((s) => (
            <button key={s} type="button" onClick={() => setName(s)} className="rounded-full bg-surface-2 px-2.5 py-1 text-xs font-semibold text-muted hover:text-text">{s}</button>
          ))}
        </div>
      )}
      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onDone}>Cancel</Button>
        <Button type="submit" loading={save.isPending} disabled={!name.trim()}>{initial ? 'Save' : 'Create'}</Button>
      </div>
    </form>
  );
}

function CollectionHeader({ c, onEdit, onDeleted }: { c: Collection; onEdit: () => void; onDeleted: () => void }) {
  const share = useCollectionMutation((shared: boolean) => collectionsApi.update(c.id, { shared }));
  const del = useCollectionMutation(() => collectionsApi.remove(c.id));
  const link = c.shareToken ? absoluteUrl(`/collections/shared/${c.shareToken}`) : null;
  return (
    <div className="mb-4 flex flex-wrap items-start gap-3">
      <div className="min-w-0 flex-1">
        <h1 className="text-2xl font-bold">{c.name}</h1>
        {c.description && <p className="mt-1 text-sm text-muted">{c.description}</p>}
        <p className="mt-1 flex items-center gap-1.5 text-xs text-faint">
          {c.shared ? <><Share2 size={12} /> Shared via link</> : <><Lock size={12} /> Private, only you can see it</>} · {c.itemCount} items
        </p>
      </div>
      <div className="flex items-center gap-2">
        {c.shared && link && (
          <Button variant="secondary" size="sm" onClick={() => navigator.clipboard.writeText(link).then(() => toast.success('Share link copied'), () => toast.error('Could not copy'))}>
            <Copy size={14} /> Copy link
          </Button>
        )}
        <Menu>
          <MenuTrigger aria-label="Collection actions" className="inline-flex h-8 w-8 items-center justify-center rounded-full bg-surface-2 hover:bg-surface-3"><MoreHorizontal size={16} /></MenuTrigger>
          <MenuContent>
            <MenuItem icon={<Pencil />} onSelect={onEdit}>Rename / describe</MenuItem>
            <MenuItem icon={c.shared ? <Lock /> : <Share2 />} onSelect={() => share.mutate(!c.shared, { onSuccess: () => toast.success(c.shared ? 'Collection is private again; the old link no longer works' : 'Share link created'), onError: (e) => toast.error(errorMessage(e)) })}>
              {c.shared ? 'Make private (revokes link)' : 'Share with a link'}
            </MenuItem>
            <MenuSeparator />
            <MenuItem icon={<Trash2 />} danger onSelect={() => del.mutate(undefined, { onSuccess: () => { toast.success('Collection deleted'); onDeleted(); }, onError: (e) => toast.error(errorMessage(e)) })}>
              Delete collection
            </MenuItem>
          </MenuContent>
        </Menu>
      </div>
    </div>
  );
}

/** Personal knowledge library: collections sidebar, search, tag and type filters, grid/list, manual ordering. */
export default function LibraryPage() {
  useDocumentTitle('Library');
  const [params, setParams] = useSearchParams();
  const selected = params.get('c') ? Number(params.get('c')) : null;
  const tag = params.get('tag') ?? '';
  const type = (params.get('type') ?? '') as '' | 'POST' | 'COMMENT';
  const [q, setQ] = useState('');
  const query = useDebouncedValue(q, 250);
  const [layout, setLayout] = useState<'list' | 'grid'>('list');
  const [form, setForm] = useState<'new' | Collection | null>(null);
  const cols = useMyCollections();
  const tags = useLibraryTags();
  const items = useCollectionItems(selected, { q: query, tag, type });
  const [order, setOrder] = useState<CollectionItem[]>([]);
  const reorder = useCollectionMutation((ids: number[]) => collectionsApi.reorder(selected!, ids));
  useEffect(() => setOrder(items.data ?? []), [items.data]);

  const current = cols.data?.find((c) => c.id === selected) ?? null;
  const canReorder = !!current && !query && !tag && !type;
  const set = (k: string, v: string | null) => {
    const n = new URLSearchParams(params);
    if (v) n.set(k, v); else n.delete(k);
    setParams(n, { replace: true });
  };

  const move = (from: number, to: number) => {
    const next = moveItem(order, from, to);
    if (next === order) return;
    setOrder(next);
    reorder.mutate(next.map((i) => i.id), { onError: (e) => { toast.error(errorMessage(e)); setOrder(items.data ?? []); } });
  };

  return (
    <div className="mx-auto flex w-full max-w-[1200px] gap-6 px-3 py-4 sm:px-6">
      <aside className="hidden w-60 shrink-0 md:block">
        <div className="sticky top-[72px] space-y-1">
          <Button className="mb-3 w-full" onClick={() => setForm('new')}><FolderPlus size={16} /> New collection</Button>
          <button type="button" onClick={() => set('c', null)} className={cn('flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-sm', selected == null ? 'bg-surface-2 font-semibold' : 'hover:bg-surface-2/70')}>
            <Library size={16} /> All saved items
          </button>
          {cols.isPending && Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-9" />)}
          {cols.data?.map((c) => (
            <button key={c.id} type="button" onClick={() => set('c', String(c.id))} className={cn('flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-sm', selected === c.id ? 'bg-surface-2 font-semibold' : 'hover:bg-surface-2/70')}>
              <span className="min-w-0 flex-1 truncate">{c.name}</span>
              {c.shared ? <Share2 size={12} className="text-faint" /> : <Lock size={12} className="text-faint" />}
              <span className="text-xs text-faint">{c.itemCount}</span>
            </button>
          ))}
          {tags.data && tags.data.length > 0 && (
            <div className="pt-4">
              <div className="px-3 pb-1.5 text-xs font-semibold uppercase tracking-wider text-muted">Tags</div>
              <div className="flex flex-wrap gap-1.5 px-3">
                {tags.data.map((t) => (
                  <button key={t.tag} type="button" onClick={() => set('tag', tag === t.tag ? null : t.tag)} className={cn('rounded-full px-2 py-0.5 text-xs font-semibold', tag === t.tag ? 'bg-brand text-brand-fg' : 'bg-surface-2 text-muted hover:text-text')}>
                    #{t.tag} <span className="opacity-60">{t.count}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      </aside>

      <div className="min-w-0 flex-1">
        <div className="mb-3 md:hidden">
          <select value={selected ?? ''} onChange={(e) => set('c', e.target.value || null)} aria-label="Collection" className="h-10 w-full rounded-full border border-border bg-surface-2 px-4 text-sm">
            <option value="">All saved items</option>
            {cols.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>

        {current ? (
          <CollectionHeader c={current} onEdit={() => setForm(current)} onDeleted={() => set('c', null)} />
        ) : (
          <div className="mb-4 flex items-center justify-between gap-3">
            <div>
              <h1 className="text-2xl font-bold">Your library</h1>
              <p className="text-sm text-muted">Everything you've filed into collections. Collections are private unless you share them.</p>
            </div>
            <Button className="md:hidden" size="sm" onClick={() => setForm('new')}><FolderPlus size={15} /> New</Button>
          </div>
        )}

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <div className="relative min-w-[200px] flex-1">
            <Search size={16} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" />
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search titles, notes and saved comments" aria-label="Search library" className="h-10 w-full rounded-full bg-surface-2 pl-10 pr-4 text-sm placeholder:text-muted focus:outline-none focus:ring-2 focus:ring-brand/30" />
          </div>
          <div className="flex rounded-full bg-surface-2 p-1 text-xs font-semibold" role="group" aria-label="Item type">
            {([['', 'All'], ['POST', 'Posts'], ['COMMENT', 'Comments']] as const).map(([v, l]) => (
              <button key={v} type="button" aria-pressed={type === v} onClick={() => set('type', v || null)} className={cn('rounded-full px-3 py-1.5', type === v ? 'bg-surface-3 text-text' : 'text-muted hover:text-text')}>{l}</button>
            ))}
          </div>
          <div className="flex rounded-full bg-surface-2 p-1" role="group" aria-label="Layout">
            <button type="button" aria-label="List view" aria-pressed={layout === 'list'} onClick={() => setLayout('list')} className={cn('rounded-full p-1.5', layout === 'list' ? 'bg-surface-3' : 'text-muted')}><List size={15} /></button>
            <button type="button" aria-label="Grid view" aria-pressed={layout === 'grid'} onClick={() => setLayout('grid')} className={cn('rounded-full p-1.5', layout === 'grid' ? 'bg-surface-3' : 'text-muted')}><LayoutGrid size={15} /></button>
          </div>
          {tag && <button type="button" onClick={() => set('tag', null)} className="rounded-full bg-brand px-3 py-1.5 text-xs font-semibold text-brand-fg">#{tag} ✕</button>}
        </div>

        {canReorder && order.length > 1 && <p className="mb-2 text-xs text-faint">Drag items (or use the arrows) to set their order.</p>}

        {items.isPending && <div className="space-y-3">{Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-28 rounded-2xl" />)}</div>}
        {items.isError && <ErrorState error={items.error} onRetry={() => items.refetch()} />}
        {items.data && order.length === 0 && (
          <EmptyState
            icon={Library}
            title={query || tag || type ? 'Nothing matches those filters' : current ? 'This collection is empty' : 'Your library is empty'}
            description={query || tag || type ? 'Try a different search or clear the filters.' : 'Use “Save to collection” on any post or comment to start building your library.'}
          />
        )}
        <div className={cn(layout === 'grid' ? 'grid gap-3 sm:grid-cols-2 xl:grid-cols-3' : 'space-y-3')}>
          {order.map((it, i) => (
            <LibraryItem
              key={it.id}
              item={it}
              layout={layout}
              collections={cols.data ?? []}
              onTag={(t) => set('tag', t)}
              reorder={canReorder ? { index: i, count: order.length, onMove: move } : undefined}
            />
          ))}
        </div>
      </div>

      <Dialog open={form !== null} onOpenChange={(o) => !o && setForm(null)} title={form && form !== 'new' ? 'Edit collection' : 'New collection'} description="Collections are private by default.">
        {form !== null && <CollectionForm initial={form === 'new' ? undefined : form} onDone={() => setForm(null)} />}
      </Dialog>
    </div>
  );
}
