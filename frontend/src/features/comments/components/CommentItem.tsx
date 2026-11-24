import { CheckCircle2, ChevronRight, Flag, FolderPlus, MessageSquare, MoreHorizontal, Pencil, Plus, Share2, ShieldX, Trash2 } from 'lucide-react';
import { modApi, useModMutation } from '@/features/moderation/api';
import { ReasonDialog } from '@/features/moderation/components/ReasonDialog';
import { ReportDialog } from '@/features/moderation/components/ReportDialog';
import { AddToCollectionDialog } from '@/features/collections/components/AddToCollectionDialog';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar } from '@/components/ui/Display';
import { Dialog } from '@/components/ui/Dialog';
import { Menu, MenuContent, MenuItem, MenuTrigger } from '@/components/ui/Menu';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { VoteControl } from '@/features/posts/components/VoteControl';
import { ReactionBar } from '@/features/reactions/components/ReactionBar';
import { errorMessage } from '@/services/apiError';
import type { CommentNode } from '@/types/api';
import { useIsNewComment } from '@/features/live/newComments';
import { ExpertiseChip } from '@/features/reputation/components/ExpertiseChip';
import { absoluteUrl, cn, timeAgo } from '@/utils/format';
import { useCommentVote, useCreateComment, useDeleteComment, useUpdateComment } from '../hooks';
import { CommentComposer } from './CommentComposer';
import { useThread } from './threadContext';
import { Markdown } from './Markdown';

export function CommentItem({ node, level }: { node: CommentNode; level: number }) {
  const t = useThread();
  const { user, isAuthed } = useAuth();
  // Comments from people the viewer muted arrive collapsed; they stay one click away (mutes are private).
  const [collapsed, setCollapsed] = useState(!!node.muted);
  const [replying, setReplying] = useState(false);
  const [editing, setEditing] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [collect, setCollect] = useState(false);
  const [reporting, setReporting] = useState(false);
  const [removing, setRemoving] = useState(false);
  const mod = useModMutation(t.communitySlug, ({ verb, reason }: { verb: 'remove' | 'approve'; reason?: string }) => modApi.comment(node.id, verb, reason));
  const vote = useCommentVote(t.postId, node.id);
  const create = useCreateComment(t.postId);
  const update = useUpdateComment(t.postId);
  const del = useDeleteComment(t.postId);
  const isNew = useIsNewComment(node.id);
  const isOwner = !!user && node.author?.id === user.id && !node.deleted;
  const gone = node.deleted || node.removed;
  const atVisualLimit = level >= t.maxLevel;
  const hiddenReplies = node.moreReplies > 0 ? node.moreReplies : Math.max(0, node.replyCount - node.replies.length);
  const name = node.author?.username ?? '[deleted]';

  const hasChildren = !collapsed && (node.replies.length > 0 || hiddenReplies > 0 || (atVisualLimit && node.replyCount > 0));
  const actionCls = 'inline-flex h-8 items-center gap-1.5 rounded-full px-2.5 text-xs font-semibold text-muted hover:bg-surface-2 hover:text-text';

  return (
    <div
      id={`c-${node.id}`}
      data-new={isNew || undefined}
      className={cn(
        'relative scroll-mt-24 rounded-md transition-[background-color,box-shadow] duration-1000',
        isNew && 'bg-brand/10 shadow-[inset_2px_0_0_rgb(var(--brand))]',
      )}
    >
      <div className="flex gap-2">
        {/* avatar + thread line column */}
        <div className="flex w-7 shrink-0 flex-col items-center pt-2">
          {node.author && !gone ? (
            <Link to={`/u/${node.author.username}`} aria-hidden="true" tabIndex={-1}>
              <Avatar src={node.author.avatarUrl} name={node.author.username} size="sm" className="h-7 w-7" />
            </Link>
          ) : (
            <span className="h-7 w-7 rounded-full bg-surface-3" aria-hidden="true" />
          )}
          {hasChildren && (
            <button
              type="button"
              aria-label={`Collapse thread by ${name}`}
              onClick={() => setCollapsed(true)}
              className="group mt-1 flex w-4 flex-1 justify-center"
            >
              <span className="h-full w-px bg-border transition-colors group-hover:w-0.5 group-hover:bg-text/70" />
            </button>
          )}
        </div>

        <div className="min-w-0 flex-1">
          <article className="pt-2" aria-label={`Comment by ${name}`}>
            <header className="flex h-7 items-center gap-1.5 text-xs text-muted">
              {node.author ? (
                <Link to={`/u/${node.author.username}`} className="font-semibold text-text hover:underline">{node.author.username}</Link>
              ) : (
                <span className="italic text-faint">[deleted]</span>
              )}
              {t.opId != null && node.author?.id === t.opId && !gone && (
                <span className="rounded-full bg-brand/15 px-1.5 text-[10px] font-bold uppercase text-brand">OP</span>
              )}
              {!gone && <ExpertiseChip postId={t.postId} userId={node.author?.id} />}
              <span aria-hidden="true">•</span>
              <time dateTime={node.createdAt} title={new Date(node.createdAt).toLocaleString()}>{timeAgo(node.createdAt)}</time>
              {node.editedAt && !gone && <span className="text-faint">(edited)</span>}
              {node.pending && (
                <span className="rounded-full bg-warning/15 px-1.5 text-[10px] font-bold uppercase text-warning" title="Held by the spam checks or a community automod rule. Only you and the moderators can see it until it’s approved.">
                  Awaiting review
                </span>
              )}
              {node.muted && collapsed && <span className="text-faint">· muted</span>}
              {collapsed && (
                <button type="button" onClick={() => setCollapsed(false)} aria-expanded={false} className="ml-1 inline-flex items-center gap-1 rounded-full bg-surface-2 px-2 py-0.5 font-semibold text-text hover:bg-surface-3">
                  <Plus size={12} /> {node.replyCount > 0 ? `${node.replyCount} more` : 'Show'}
                </button>
              )}
            </header>

            {!collapsed && (
              <>
                <div className="mt-0.5">
                  {editing ? (
                    <CommentComposer
                      initial={node.body}
                      submitLabel="Save"
                      // eslint-disable-next-line jsx-a11y/no-autofocus -- opened by the user clicking Reply/Edit; the box they asked for gets focus
                      autoFocus
                      loading={update.isPending}
                      onCancel={() => setEditing(false)}
                      onSubmit={(body) =>
                        update.mutate({ id: node.id, body }, { onSuccess: () => setEditing(false), onError: (e) => toast.error(errorMessage(e)) })
                      }
                    />
                  ) : node.blocked ? (
                    <p className="text-sm italic text-faint">Comment from a user you blocked</p>
                  ) : gone ? (
                    <p className="text-sm italic text-faint">{node.removed ? '[removed by moderators]' : '[deleted]'}</p>
                  ) : (
                    <Markdown source={node.body} />
                  )}
                </div>

                {!editing && (
                  <div className="-ml-1.5 mt-0.5 flex flex-wrap items-center gap-0.5">
                    <VoteControl
                      orientation="horizontal"
                      size="sm"
                      score={node.score}
                      myVote={node.myVote}
                      disabled={gone}
                      label={`comment by ${name}`}
                      onVote={(v) => vote.mutate(v, { onError: (e) => toast.error(errorMessage(e)) })}
                    />
                    {isAuthed && !t.locked && !gone && (
                      <button type="button" onClick={() => setReplying((r) => !r)} className={actionCls}>
                        <MessageSquare size={15} /> Reply
                      </button>
                    )}
                    {!gone && !node.pending && !node.blocked && <ReactionBar type="COMMENT" id={node.id} compact disabled={t.locked} />}
                    {!gone && (
                      <button
                        type="button"
                        className={actionCls}
                        onClick={() =>
                          navigator.clipboard
                            .writeText(absoluteUrl(`${window.location.pathname}#c-${node.id}`))
                            .then(() => toast.success('Link copied'), () => toast.error('Could not copy the link'))
                        }
                      >
                        <Share2 size={14} /> Share
                      </button>
                    )}
                    {isAuthed && !gone && (
                      <button type="button" className={actionCls} onClick={() => setCollect(true)}>
                        <FolderPlus size={14} /> Save
                      </button>
                    )}
                    {(isOwner || (isAuthed && !gone && !node.deleted) || (t.canModerate && !node.deleted)) && (
                      <Menu>
                        <MenuTrigger aria-label="Comment actions" className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-muted hover:bg-surface-2 hover:text-text">
                          <MoreHorizontal size={16} />
                        </MenuTrigger>
                        <MenuContent align="start">
                          {isOwner && <MenuItem icon={<Pencil />} onSelect={() => setEditing(true)}>Edit</MenuItem>}
                          {isOwner && <MenuItem icon={<Trash2 />} danger onSelect={() => setConfirmDelete(true)}>Delete</MenuItem>}
                          {!isOwner && isAuthed && !gone && <MenuItem icon={<Flag />} onSelect={() => setReporting(true)}>Report</MenuItem>}
                          {t.canModerate && !node.deleted && (node.removed ? (
                            <MenuItem icon={<CheckCircle2 />} onSelect={() => mod.mutate({ verb: 'approve' }, { onSuccess: () => toast.success('Comment restored'), onError: (e) => toast.error(errorMessage(e)) })}>
                              Approve (restore)
                            </MenuItem>
                          ) : (
                            <>
                              <MenuItem icon={<ShieldX />} danger onSelect={() => setRemoving(true)}>Remove (moderator)</MenuItem>
                              <MenuItem icon={<CheckCircle2 />} onSelect={() => mod.mutate({ verb: 'approve' }, { onSuccess: () => toast.success(node.pending ? 'Approved and published' : 'Reports dismissed'), onError: (e) => toast.error(errorMessage(e)) })}>
                                {node.pending ? 'Approve and publish' : 'Approve (dismiss reports)'}
                              </MenuItem>
                            </>
                          ))}
                        </MenuContent>
                      </Menu>
                    )}
                  </div>
                )}

                {replying && (
                  <div className="mt-2">
                    <CommentComposer
                      // eslint-disable-next-line jsx-a11y/no-autofocus -- opened by the user clicking Reply/Edit; the box they asked for gets focus
                      autoFocus
                      submitLabel="Reply"
                      placeholder={`Reply to ${name}`}
                      loading={create.isPending}
                      onCancel={() => setReplying(false)}
                      onSubmit={(body, reset) =>
                        create.mutate(
                          { body, parentId: node.id },
                          {
                            onSuccess: () => {
                              reset();
                              setReplying(false);
                            },
                            onError: (e) => toast.error(errorMessage(e)),
                          },
                        )
                      }
                    />
                  </div>
                )}
              </>
            )}
          </article>

          {!collapsed && node.replies.length > 0 && !atVisualLimit && (
            <div>
              {node.replies.map((r) => (
                <CommentItem key={r.id} node={r} level={level + 1} />
              ))}
            </div>
          )}

          {!collapsed && atVisualLimit && node.replyCount > 0 && (
            <button type="button" onClick={() => t.focus(node.id)} className="mt-1 inline-flex items-center gap-1 rounded-full px-2 py-1 text-xs font-semibold text-brand hover:bg-surface-2">
              Continue this thread <ChevronRight size={13} />
            </button>
          )}

          {!collapsed && !atVisualLimit && hiddenReplies > 0 && (
            <Button
              variant="ghost"
              size="sm"
              className="mt-1 h-7 px-2 text-xs font-semibold text-brand"
              loading={t.loadingFor === node.id}
              onClick={() => t.loadMore(node.id)}
            >
              <Plus size={13} /> {hiddenReplies} more {hiddenReplies === 1 ? 'reply' : 'replies'}
            </Button>
          )}
        </div>
      </div>

      {reporting && (
        <ReportDialog
          target={{ type: 'COMMENT', postId: t.postId, commentId: node.id, communitySlug: t.communitySlug, label: node.body.slice(0, 120) }}
          open={reporting}
          onOpenChange={setReporting}
        />
      )}
      {t.canModerate && (
        <ReasonDialog
          open={removing}
          onOpenChange={setRemoving}
          title="Remove this comment?"
          confirmLabel="Remove"
          loading={mod.isPending}
          onConfirm={(reason) =>
            mod.mutate(
              { verb: 'remove', reason: reason || undefined },
              { onSuccess: () => { setRemoving(false); toast.success('Comment removed'); }, onError: (e) => toast.error(errorMessage(e)) },
            )
          }
        />
      )}
      {collect && (
        <AddToCollectionDialog
          target={{ postId: t.postId, commentId: node.id, title: node.body.slice(0, 120) }}
          open={collect}
          onOpenChange={setCollect}
        />
      )}
      <Dialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        size="sm"
        title="Delete comment?"
        description="The text is removed; replies stay in place under a [deleted] marker."
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmDelete(false)}>Cancel</Button>
            <Button
              variant="danger"
              loading={del.isPending}
              onClick={() =>
                del.mutate(node.id, {
                  onSuccess: () => {
                    setConfirmDelete(false);
                    toast.success('Comment deleted');
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
        <p className="line-clamp-3 text-sm text-muted">{node.body}</p>
      </Dialog>
    </div>
  );
}
