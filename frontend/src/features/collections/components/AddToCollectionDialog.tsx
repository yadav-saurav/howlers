import { Check, FolderPlus, Lock, Plus } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Skeleton } from '@/components/ui/Display';
import { Input, Textarea } from '@/components/ui/Field';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import { cn } from '@/utils/format';
import { collectionsApi, useCollectionMutation, useMembership, useMyCollections } from '../api';
import { parseTags } from '../tags';

export interface AddTarget {
  postId: number;
  commentId?: number;
  title: string;
}

/** File a post or comment into one or more collections, optionally with a note and tags. */
export function AddToCollectionDialog({ target, open, onOpenChange }: { target: AddTarget; open: boolean; onOpenChange: (o: boolean) => void }) {
  const cols = useMyCollections(open);
  const member = useMembership(target.postId, target.commentId, open);
  const [note, setNote] = useState('');
  const [tags, setTags] = useState('');
  const [newName, setNewName] = useState('');
  const create = useCollectionMutation(collectionsApi.create);
  const add = useCollectionMutation((id: number) =>
    collectionsApi.add(id, {
      type: target.commentId ? 'COMMENT' : 'POST',
      postId: target.postId,
      commentId: target.commentId,
      note: note.trim() || undefined,
      tags: parseTags(tags),
    }),
  );
  const remove = useCollectionMutation(collectionsApi.removeItem);
  const filed = new Map((member.data ?? []).map((m) => [m.collectionId, m.itemId]));

  const toggle = (collectionId: number, name: string) => {
    const itemId = filed.get(collectionId);
    if (itemId) {
      remove.mutate(itemId, { onSuccess: () => toast.success(`Removed from ${name}`), onError: (e) => toast.error(errorMessage(e)) });
    } else {
      add.mutate(collectionId, { onSuccess: () => toast.success(`Added to ${name}`), onError: (e) => toast.error(errorMessage(e)) });
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={target.commentId ? 'Save comment to a collection' : 'Save post to a collection'}
      description={target.title}
      footer={<Button variant="secondary" onClick={() => onOpenChange(false)}>Done</Button>}
    >
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="block text-xs font-semibold text-muted">
            Note (optional)
            <Textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} maxLength={2000} placeholder="Why are you saving this?" className="mt-1" />
          </label>
          <label className="block text-xs font-semibold text-muted">
            Tags (optional)
            <Input value={tags} onChange={(e) => setTags(e.target.value)} placeholder="java, roadmap" className="mt-1" />
          </label>
        </div>

        <div className="max-h-64 space-y-1 overflow-y-auto">
          {cols.isPending && Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-11" />)}
          {cols.data?.length === 0 && <p className="py-2 text-sm text-muted">No collections yet. Create your first one below.</p>}
          {cols.data?.map((c) => {
            const on = filed.has(c.id);
            return (
              <button
                key={c.id}
                type="button"
                onClick={() => toggle(c.id, c.name)}
                aria-pressed={on}
                disabled={add.isPending || remove.isPending}
                className={cn('flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors hover:bg-surface-2', on && 'bg-surface-2')}
              >
                <span className={cn('flex h-5 w-5 items-center justify-center rounded-md border', on ? 'border-accent bg-accent text-accent-fg' : 'border-border')}>
                  {on && <Check size={13} />}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-semibold">{c.name}</span>
                  <span className="block text-xs text-faint">{c.itemCount} items</span>
                </span>
                {!c.shared && <Lock size={13} className="text-faint" aria-label="Private" />}
              </button>
            );
          })}
        </div>

        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            const name = newName.trim();
            if (!name) return;
            create.mutate({ name }, {
              onSuccess: (c) => {
                setNewName('');
                add.mutate(c.id, { onSuccess: () => toast.success(`Created ${c.name} and saved`), onError: (er) => toast.error(errorMessage(er)) });
              },
              onError: (er) => toast.error(errorMessage(er)),
            });
          }}
        >
          <Input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="New collection name" maxLength={60} aria-label="New collection name" />
          <Button type="submit" variant="secondary" loading={create.isPending} disabled={!newName.trim()}>
            <Plus size={15} /> Create
          </Button>
        </form>
        <Link to="/library" className="inline-flex items-center gap-1.5 text-xs font-semibold text-brand hover:underline" onClick={() => onOpenChange(false)}>
          <FolderPlus size={13} /> Open your library
        </Link>
      </div>
    </Dialog>
  );
}
