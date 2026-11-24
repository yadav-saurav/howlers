import { ArrowDown, ArrowUp, FolderInput, GripVertical, MessageSquare, MoreHorizontal, Pencil, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Badge } from '@/components/ui/Display';
import { Input, Textarea } from '@/components/ui/Field';
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from '@/components/ui/Menu';
import { toast } from '@/components/ui/Toast';
import { postPath } from '@/features/posts/paths';
import { errorMessage } from '@/services/apiError';
import type { Collection, CollectionItem } from '@/types/knowledge';
import { cn, compactNumber, timeAgo } from '@/utils/format';
import { collectionsApi, useCollectionMutation } from '../api';
import { sizedUrl } from '@/utils/images';
import { parseTags } from '../tags';

interface Props {
  item: CollectionItem;
  collections: Collection[];
  layout: 'list' | 'grid';
  reorder?: { index: number; count: number; onMove: (from: number, to: number) => void };
  readOnly?: boolean;
  onTag?: (tag: string) => void;
}

/** One saved post/comment with its note and tags; editable in place for the owner. */
export function LibraryItem({ item, collections, layout, reorder, readOnly, onTag }: Props) {
  const [editing, setEditing] = useState(false);
  const [note, setNote] = useState(item.note);
  const [tags, setTags] = useState(item.tags.join(', '));
  const update = useCollectionMutation((body: { note?: string; tags?: string[]; collectionId?: number }) => collectionsApi.updateItem(item.id, body));
  const remove = useCollectionMutation(() => collectionsApi.removeItem(item.id));
  const p = item.post;
  const href = item.comment ? `${postPath(p)}#c-${item.comment.id}` : postPath(p);
  const thumb = p.media[0]?.url;

  return (
    <article
      className={cn('group relative rounded-2xl border border-border bg-surface p-3 transition-colors hover:border-surface-3', layout === 'grid' && 'flex flex-col')}
      draggable={!!reorder}
      onDragStart={(e) => reorder && e.dataTransfer.setData('text/plain', String(reorder.index))}
      onDragOver={(e) => reorder && e.preventDefault()}
      onDrop={(e) => {
        if (!reorder) return;
        e.preventDefault();
        const from = Number(e.dataTransfer.getData('text/plain'));
        if (Number.isFinite(from)) reorder.onMove(from, reorder.index);
      }}
    >
      <div className="flex gap-3">
        {reorder && (
          <div className="flex flex-col items-center gap-0.5 text-faint">
            <GripVertical size={16} className="cursor-grab" aria-hidden="true" />
            <button type="button" aria-label="Move up" disabled={reorder.index === 0} onClick={() => reorder.onMove(reorder.index, reorder.index - 1)} className="rounded-full p-0.5 hover:bg-surface-2 disabled:opacity-30">
              <ArrowUp size={13} />
            </button>
            <button type="button" aria-label="Move down" disabled={reorder.index === reorder.count - 1} onClick={() => reorder.onMove(reorder.index, reorder.index + 1)} className="rounded-full p-0.5 hover:bg-surface-2 disabled:opacity-30">
              <ArrowDown size={13} />
            </button>
          </div>
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5 text-xs text-muted">
            <Avatar src={p.community.iconUrl} name={p.community.slug} size="xs" />
            <Link to={`/c/${p.community.slug}`} className="font-semibold text-text hover:underline">c/{p.community.slug}</Link>
            <span>•</span>
            <span>saved {timeAgo(item.createdAt)}</span>
            {item.type === 'COMMENT' && <Badge tone="info" className="rounded-full px-2"><MessageSquare size={10} /> Comment</Badge>}
          </div>
          <Link to={href} className="mt-1 line-clamp-2 block font-semibold leading-snug hover:underline">{p.title}</Link>
          {item.comment ? (
            <blockquote className="mt-1.5 line-clamp-4 border-l-2 border-border pl-3 text-sm text-muted">
              {item.comment.excerpt}
              {item.comment.author && <span className="mt-0.5 block text-xs text-faint">— u/{item.comment.author.username}</span>}
            </blockquote>
          ) : (
            p.bodyPreview && <p className="mt-1 line-clamp-2 text-sm text-muted">{p.bodyPreview}</p>
          )}
          <div className="mt-1.5 text-xs text-faint">{compactNumber(p.score)} upvotes · {compactNumber(p.commentCount)} comments</div>
        </div>
        {thumb && layout === 'list' && <img src={sizedUrl(thumb, 320)} alt="" loading="lazy" decoding="async" className="h-20 w-20 shrink-0 rounded-xl object-cover" />}
      </div>

      {editing ? (
        <form
          className="mt-3 space-y-2"
          onSubmit={(e) => {
            e.preventDefault();
            update.mutate({ note, tags: parseTags(tags) }, { onSuccess: () => { setEditing(false); toast.success('Saved'); }, onError: (er) => toast.error(errorMessage(er)) });
          }}
        >
          <Textarea value={note} onChange={(e) => setNote(e.target.value)} rows={3} maxLength={2000} placeholder="Add a note" aria-label="Note" />
          <Input value={tags} onChange={(e) => setTags(e.target.value)} placeholder="tags, comma separated" aria-label="Tags" />
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => { setEditing(false); setNote(item.note); setTags(item.tags.join(', ')); }}>Cancel</Button>
            <Button type="submit" size="sm" loading={update.isPending}>Save</Button>
          </div>
        </form>
      ) : (
        (item.note || item.tags.length > 0) && (
          <div className="mt-3 rounded-xl bg-surface-2/70 px-3 py-2">
            {item.note && <p className="whitespace-pre-wrap text-sm">{item.note}</p>}
            {item.tags.length > 0 && (
              <div className="mt-1.5 flex flex-wrap gap-1.5">
                {item.tags.map((t) => (
                  <button key={t} type="button" onClick={() => onTag?.(t)} className="rounded-full bg-surface-3 px-2 py-0.5 text-[11px] font-semibold text-muted hover:text-text">#{t}</button>
                ))}
              </div>
            )}
          </div>
        )
      )}

      {!readOnly && !editing && (
        <div className="absolute right-2 top-2 opacity-60 group-hover:opacity-100">
          <Menu>
            <MenuTrigger aria-label="Item actions" className="inline-flex h-8 w-8 items-center justify-center rounded-full text-muted hover:bg-surface-2 hover:text-text">
              <MoreHorizontal size={16} />
            </MenuTrigger>
            <MenuContent>
              <MenuItem icon={<Pencil />} onSelect={() => setEditing(true)}>Edit note & tags</MenuItem>
              <MenuSeparator />
              <MenuLabel>Move to</MenuLabel>
              {collections.filter((c) => c.id !== item.collectionId).map((c) => (
                <MenuItem key={c.id} icon={<FolderInput />} onSelect={() => update.mutate({ collectionId: c.id }, { onSuccess: () => toast.success(`Moved to ${c.name}`), onError: (er) => toast.error(errorMessage(er)) })}>
                  {c.name}
                </MenuItem>
              ))}
              <MenuSeparator />
              <MenuItem icon={<Trash2 />} danger onSelect={() => remove.mutate(undefined, { onSuccess: () => toast.success('Removed'), onError: (er) => toast.error(errorMessage(er)) })}>
                Remove from collection
              </MenuItem>
            </MenuContent>
          </Menu>
        </div>
      )}
    </article>
  );
}
