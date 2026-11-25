import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Field, Input } from '@/components/ui/Field';
import { toast } from '@/components/ui/Toast';
import { TagInput } from '@/features/communities/components/TagInput';
import { errorMessage } from '@/services/apiError';
import type { PostDetail } from '@/types/api';
import { useUpdatePost } from '../hooks';
import { RichTextEditor } from './RichTextEditor';

export function EditPostDialog({ post, open, onOpenChange }: { post: PostDetail; open: boolean; onOpenChange: (o: boolean) => void }) {
  const update = useUpdatePost(post.id);
  const [title, setTitle] = useState(post.title);
  const [body, setBody] = useState(post.bodyHtml);
  const [tags, setTags] = useState(post.tags);
  const titleError = !title.trim() ? 'Title is required' : title.length > 300 ? 'At most 300 characters' : undefined;

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title="Edit post"
      size="lg"
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button
            disabled={!!titleError}
            loading={update.isPending}
            onClick={() =>
              update.mutate(
                {
                  title: title.trim(),
                  bodyHtml: post.type === 'TEXT' || post.type === 'IMAGE' || post.type === 'LINK' ? body : undefined,
                  tags,
                },
                {
                  onSuccess: () => {
                    toast.success('Post updated');
                    onOpenChange(false);
                  },
                  onError: (e) => toast.error(errorMessage(e)),
                },
              )
            }
          >
            Save changes
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label="Title" error={titleError}>{(a) => <Input {...a} value={title} maxLength={300} onChange={(e) => setTitle(e.target.value)} />}</Field>
        {post.type !== 'POLL' && (
          <div>
            <span className="mb-1.5 block text-sm font-medium">Body</span>
            <RichTextEditor value={body} onChange={setBody} />
          </div>
        )}
        <div>
          <Field label="Tags" optional>{(a) => <TagInput id={a.id} value={tags} onChange={setTags} />}</Field>
        </div>
      </div>
    </Dialog>
  );
}
