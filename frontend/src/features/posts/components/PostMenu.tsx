import { Bookmark, BookmarkCheck, EyeOff, Flag, FolderPlus, Link2, MoreHorizontal, Pencil, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from '@/components/ui/Menu';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { AddToCollectionDialog } from '@/features/collections/components/AddToCollectionDialog';
import { modApi, useModMutation } from '@/features/moderation/api';
import { BanDialog } from '@/features/moderation/components/BanDialog';
import { PostModMenuItems } from '@/features/moderation/components/PostModMenuItems';
import { ReasonDialog } from '@/features/moderation/components/ReasonDialog';
import { ReportDialog } from '@/features/moderation/components/ReportDialog';
import { errorMessage } from '@/services/apiError';
import type { PostSummary } from '@/types/api';
import { absoluteUrl } from '@/utils/format';
import { useDeletePost, useHidePost, useSavePost } from '../hooks';
import { postPath } from '../paths';

export async function copyPostLink(post: Pick<PostSummary, 'id' | 'community'>) {
  try {
    await navigator.clipboard.writeText(absoluteUrl(postPath(post)));
    toast.success('Link copied');
  } catch {
    toast.error('Could not copy the link');
  }
}

/** Overflow menu: copy link, save, hide, report, owner-only edit/delete and (when `canModerate`) moderator tools. */
export function PostMenu({
  post,
  onEdit,
  afterDelete,
  canModerate,
}: {
  post: PostSummary;
  onEdit?: () => void;
  afterDelete?: () => void;
  canModerate?: boolean;
}) {
  const { user, isAuthed } = useAuth();
  const navigate = useNavigate();
  const save = useSavePost(post.id);
  const hide = useHidePost(post.id);
  const del = useDeletePost(post.id);
  const [confirm, setConfirm] = useState(false);
  const [collect, setCollect] = useState(false);
  const [reporting, setReporting] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [banning, setBanning] = useState(false);
  const remove = useModMutation(post.community.slug, (reason: string) => modApi.post(post.id, 'remove', reason || undefined));
  const isOwner = !!user && post.author?.id === user.id;

  return (
    <>
      <Menu>
        <MenuTrigger
          aria-label="More actions"
          onClick={(e) => e.stopPropagation()}
          className="inline-flex h-8 w-8 items-center justify-center rounded-full text-muted hover:bg-surface-2 hover:text-text"
        >
          <MoreHorizontal size={17} />
        </MenuTrigger>
        <MenuContent>
          <MenuItem icon={<Link2 />} onSelect={() => copyPostLink(post)}>Copy link</MenuItem>
          {isAuthed && (
            <>
              <MenuItem
                icon={post.saved ? <BookmarkCheck /> : <Bookmark />}
                onSelect={() =>
                  save.mutate(!post.saved, {
                    onSuccess: () => toast.success(post.saved ? 'Removed from saved' : 'Saved'),
                    onError: (e) => toast.error(errorMessage(e)),
                  })
                }
              >
                {post.saved ? 'Unsave' : 'Save'}
              </MenuItem>
              <MenuItem icon={<FolderPlus />} onSelect={() => setCollect(true)}>Save to collection</MenuItem>
              {!isOwner && <MenuItem icon={<Flag />} onSelect={() => setReporting(true)}>Report</MenuItem>}
              <MenuItem
                icon={<EyeOff />}
                onSelect={() =>
                  hide.mutate(undefined, {
                    onSuccess: () => toast.info('Post hidden from your feeds'),
                    onError: (e) => toast.error(errorMessage(e)),
                  })
                }
              >
                Hide
              </MenuItem>
            </>
          )}
          {isOwner && (
            <>
              <MenuSeparator />
              <MenuItem icon={<Pencil />} onSelect={() => (onEdit ? onEdit() : navigate(`${postPath(post)}?edit=1`))}>
                Edit
              </MenuItem>
              <MenuItem icon={<Trash2 />} danger onSelect={() => setConfirm(true)}>
                Delete
              </MenuItem>
            </>
          )}
          {canModerate && <PostModMenuItems post={post} onRemove={() => setRemoving(true)} onBan={() => setBanning(true)} />}
        </MenuContent>
      </Menu>
      {reporting && (
        <ReportDialog
          target={{ type: 'POST', postId: post.id, communitySlug: post.community.slug, label: post.title }}
          open={reporting}
          onOpenChange={setReporting}
        />
      )}
      {canModerate && (
        <ReasonDialog
          open={removing}
          onOpenChange={setRemoving}
          title="Remove this post?"
          confirmLabel="Remove"
          loading={remove.isPending}
          onConfirm={(reason) =>
            remove.mutate(reason, {
              onSuccess: () => {
                setRemoving(false);
                toast.success('Post removed');
              },
              onError: (e) => toast.error(errorMessage(e)),
            })
          }
        />
      )}
      {canModerate && banning && post.author && (
        <BanDialog slug={post.community.slug} username={post.author.username} open={banning} onOpenChange={setBanning} />
      )}
      {collect && <AddToCollectionDialog target={{ postId: post.id, title: post.title }} open={collect} onOpenChange={setCollect} />}
      <Dialog
        open={confirm}
        onOpenChange={setConfirm}
        title="Delete this post?"
        description="The post is removed for everyone. Comments stay attached to a [deleted] placeholder."
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirm(false)}>Cancel</Button>
            <Button
              variant="danger"
              loading={del.isPending}
              onClick={() =>
                del.mutate(undefined, {
                  onSuccess: () => {
                    setConfirm(false);
                    toast.success('Post deleted');
                    afterDelete?.();
                  },
                  onError: (e) => toast.error(errorMessage(e)),
                })
              }
            >
              Delete
            </Button>
          </>
        }
      >
        <p className="truncate text-sm text-muted">“{post.title}”</p>
      </Dialog>
    </>
  );
}
