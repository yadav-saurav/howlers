import { BarChart3, Eye, FileText, Image as ImageIcon, Link2, Pencil, Plus, Trash2, X } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Button, IconButton, buttonClasses } from '@/components/ui/Button';
import { Badge, Card } from '@/components/ui/Display';
import { Field, FormAlert, Input, Select } from '@/components/ui/Field';
import { ImageUpload } from '@/components/ui/ImageUpload';
import { EmptyState } from '@/components/ui/States';
import { Segmented } from '@/components/ui/Tabs';
import { toast } from '@/components/ui/Toast';
import { TagInput } from '@/features/communities/components/TagInput';
import { useCommunity, useMyCommunities } from '@/features/communities/hooks';
import type { MediaUpload } from '@/features/posts/api';
import { RichTextEditor } from '@/features/posts/components/RichTextEditor';
import { useCreatePost, useUploadMedia } from '@/features/posts/hooks';
import { postPath } from '@/features/posts/paths';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';
import { ApiError, errorMessage } from '@/services/apiError';
import type { PostType } from '@/types/api';

const TYPES: { value: PostType; label: string; icon: JSX.Element }[] = [
  { value: 'TEXT', label: 'Text', icon: <FileText size={15} /> },
  { value: 'IMAGE', label: 'Images', icon: <ImageIcon size={15} /> },
  { value: 'LINK', label: 'Link', icon: <Link2 size={15} /> },
  { value: 'POLL', label: 'Poll', icon: <BarChart3 size={15} /> },
];

const CLOSE_OPTIONS = [
  { value: '', label: 'Never closes' },
  { value: '1', label: '1 day' },
  { value: '3', label: '3 days' },
  { value: '7', label: '7 days' },
];

export default function SubmitPage() {
  useDocumentTitle('Create post');
  const { slug: routeSlug } = useParams();
  const navigate = useNavigate();
  const mine = useMyCommunities();
  const [slug, setSlug] = useState(routeSlug ?? '');
  const community = useCommunity(slug);
  const [type, setType] = useState<PostType>('TEXT');
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [url, setUrl] = useState('');
  const [media, setMedia] = useState<MediaUpload[]>([]);
  const [options, setOptions] = useState(['', '']);
  const [multiple, setMultiple] = useState(false);
  const [closeDays, setCloseDays] = useState('3');
  const [flairId, setFlairId] = useState('');
  const [tags, setTags] = useState<string[]>([]);
  const [preview, setPreview] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const upload = useUploadMedia();
  const create = useCreatePost();

  const communityOptions = useMemo(() => {
    const list = mine.data ?? [];
    if (routeSlug && !list.some((c) => c.slug === routeSlug)) return [{ slug: routeSlug, id: -1 }, ...list];
    return list;
  }, [mine.data, routeSlug]);

  const validate = () => {
    const e: Record<string, string> = {};
    if (!slug) e.communitySlug = 'Choose a community';
    if (!title.trim()) e.title = 'Add a title';
    else if (title.length > 300) e.title = 'At most 300 characters';
    if (type === 'LINK') {
      try {
        const u = new URL(url.trim());
        if (!/^https?:$/.test(u.protocol)) throw new Error();
      } catch {
        e.url = 'Enter a full http(s) URL';
      }
    }
    if (type === 'IMAGE' && media.length === 0) e.mediaIds = 'Upload at least one image';
    if (type === 'POLL') {
      const filled = options.map((o) => o.trim()).filter(Boolean);
      if (filled.length < 2) e.poll = 'Add at least two options';
      else if (new Set(filled.map((o) => o.toLowerCase())).size !== filled.length) e.poll = 'Options must be different';
    }
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const submit = () => {
    setFormError(null);
    if (!validate()) return;
    create.mutate(
      {
        communitySlug: slug,
        type,
        title: title.trim(),
        bodyHtml: body || undefined,
        url: type === 'LINK' ? url.trim() : undefined,
        mediaIds: type === 'IMAGE' ? media.map((m) => m.id) : undefined,
        flairId: flairId ? Number(flairId) : null,
        tags,
        poll:
          type === 'POLL'
            ? {
                options: options.map((o) => o.trim()).filter(Boolean),
                multiple,
                closesAt: closeDays ? new Date(Date.now() + Number(closeDays) * 86_400_000).toISOString() : null,
              }
            : undefined,
      },
      {
        onSuccess: (p) => {
          toast.success('Post published');
          navigate(postPath(p));
        },
        onError: (err) => {
          if (err instanceof ApiError && Object.keys(err.fieldErrors).length) setErrors(err.fieldErrors);
          setFormError(errorMessage(err));
        },
      },
    );
  };

  if (mine.data && mine.data.length === 0 && !routeSlug) {
    return (
      <PageColumns>
        <EmptyState
          icon={Pencil}
          title="Join a community first"
          description="Posts live inside communities. Join one that fits your topic, or create your own."
          action={
            <div className="flex gap-2">
              <Link to="/communities" className={buttonClasses('primary', 'sm')}>Browse communities</Link>
              <Link to="/communities/new" className={buttonClasses('outline', 'sm')}>Create community</Link>
            </div>
          }
        />
      </PageColumns>
    );
  }

  const flairs = community.data?.flairs ?? [];
  const cannotPost = community.data && !community.data.viewer.canPost;

  return (
    <PageColumns>
      <PageHeader
        title="Create a post"
        actions={
          <Button variant="ghost" size="sm" onClick={() => setPreview((v) => !v)} aria-pressed={preview}>
            {preview ? <><Pencil size={15} /> Edit</> : <><Eye size={15} /> Preview</>}
          </Button>
        }
      />
      <Card className="mx-0 rounded-none border-x-0 p-4 sm:rounded-lg sm:border-x sm:p-6">
        <div className="space-y-5">
          <FormAlert message={formError} />
          <Field label="Community" error={errors.communitySlug}>
            {(a) => (
              <Select {...a} value={slug} onChange={(e) => { setSlug(e.target.value); setFlairId(''); }}>
                <option value="">Choose a community</option>
                {communityOptions.map((c) => <option key={c.slug} value={c.slug}>c/{c.slug}</option>)}
              </Select>
            )}
          </Field>
          {cannotPost && (
            <p role="alert" className="rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-sm text-warning">
              Only approved contributors can post in c/{slug}.
            </p>
          )}

          {preview ? (
            <div className="rounded-md border border-border p-4">
              <div className="text-xs text-muted">c/{slug || '…'} · preview</div>
              <h2 className="mt-1 font-serif text-xl font-semibold">{title || 'Untitled'}</h2>
              <div className="mt-1.5 flex flex-wrap gap-1.5">
                {flairId && flairs.find((f) => String(f.id) === flairId) && (
                  <Badge color={flairs.find((f) => String(f.id) === flairId)!.color}>{flairs.find((f) => String(f.id) === flairId)!.text}</Badge>
                )}
                {tags.map((t) => <span key={t} className="text-xs text-faint">#{t}</span>)}
              </div>
              {type === 'LINK' && url && <p className="mt-3 truncate text-sm text-accent">{url}</p>}
              {type === 'IMAGE' && media.length > 0 && (
                <div className="mt-3 grid grid-cols-2 gap-2">{media.map((m) => <img key={m.id} src={m.url} alt="" className="rounded-md border border-border" />)}</div>
              )}
              {type === 'POLL' && (
                <ul className="mt-3 space-y-1.5">{options.filter((o) => o.trim()).map((o, i) => <li key={i} className="rounded-md border border-border px-3 py-2 text-sm">{o}</li>)}</ul>
              )}
              {/* Preview of the author's own unsent editor output; the server sanitizes on submit. */}
              {body && <div className="prose-howlers mt-3" dangerouslySetInnerHTML={{ __html: body }} />}
            </div>
          ) : (
            <>
              <Segmented label="Post type" value={type} options={TYPES} onChange={setType} />
              <Field label="Title" error={errors.title} hint={`${title.length}/300`}>
                {(a) => <Input {...a} value={title} maxLength={300} onChange={(e) => setTitle(e.target.value)} placeholder="A specific, descriptive title" />}
              </Field>

              {type === 'LINK' && (
                <Field label="URL" error={errors.url}>
                  {(a) => <Input {...a} type="url" inputMode="url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://" />}
                </Field>
              )}

              {type === 'IMAGE' && (
                <div>
                  <span className="mb-1.5 block text-sm font-medium">Images</span>
                  <div className="flex flex-wrap gap-2">
                    {media.map((m) => (
                      <div key={m.id} className="relative">
                        <img src={m.url} alt="" className="h-24 w-24 rounded-md border border-border object-cover" />
                        <IconButton label="Remove image" size="sm" className="absolute right-1 top-1 h-6 w-6 bg-bg/80" onClick={() => setMedia(media.filter((x) => x.id !== m.id))}>
                          <X size={13} />
                        </IconButton>
                      </div>
                    ))}
                    {media.length < 10 && (
                      <div className="flex h-24 items-center">
                        <ImageUpload
                          label={media.length ? 'Add another' : 'Upload image'}
                          maxMb={10}
                          loading={upload.isPending}
                          onFile={(f) => upload.mutate(f, { onSuccess: (m) => setMedia((cur) => [...cur, m]), onError: (e) => toast.error(errorMessage(e)) })}
                        />
                      </div>
                    )}
                  </div>
                  {errors.mediaIds && <p role="alert" className="mt-1.5 text-xs text-danger">{errors.mediaIds}</p>}
                </div>
              )}

              {type === 'POLL' && (
                <fieldset>
                  <legend className="mb-1.5 text-sm font-medium">Options</legend>
                  <div className="space-y-2">
                    {options.map((o, i) => (
                      <div key={i} className="flex gap-2">
                        <Input aria-label={`Option ${i + 1}`} value={o} maxLength={80} placeholder={`Option ${i + 1}`} onChange={(e) => setOptions(options.map((x, j) => (j === i ? e.target.value : x)))} />
                        {options.length > 2 && (
                          <IconButton label={`Remove option ${i + 1}`} size="sm" onClick={() => setOptions(options.filter((_, j) => j !== i))}><Trash2 size={15} /></IconButton>
                        )}
                      </div>
                    ))}
                  </div>
                  {errors.poll && <p role="alert" className="mt-1.5 text-xs text-danger">{errors.poll}</p>}
                  <div className="mt-2 flex flex-wrap items-center gap-4">
                    {options.length < 8 && (
                      <Button variant="ghost" size="sm" onClick={() => setOptions([...options, ''])}><Plus size={15} /> Add option</Button>
                    )}
                    <label className="flex items-center gap-2 text-sm">
                      <input type="checkbox" checked={multiple} onChange={(e) => setMultiple(e.target.checked)} className="accent-[rgb(var(--accent))]" />
                      Allow multiple choices
                    </label>
                    <label className="flex items-center gap-2 text-sm">
                      <span className="text-muted">Closes</span>
                      <Select value={closeDays} onChange={(e) => setCloseDays(e.target.value)} className="h-8 w-36 text-sm" aria-label="Poll duration">
                        {CLOSE_OPTIONS.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
                      </Select>
                    </label>
                  </div>
                </fieldset>
              )}

              <div>
                <span className="mb-1.5 block text-sm font-medium">
                  {type === 'TEXT' ? 'Body' : 'Context'} <span className="text-xs font-normal text-faint">optional</span>
                </span>
                <RichTextEditor value={body} onChange={setBody} />
                {errors.bodyHtml && <p role="alert" className="mt-1.5 text-xs text-danger">{errors.bodyHtml}</p>}
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                {flairs.length > 0 && (
                  <Field label="Flair" optional>
                    {(a) => (
                      <Select {...a} value={flairId} onChange={(e) => setFlairId(e.target.value)}>
                        <option value="">No flair</option>
                        {flairs.map((f) => <option key={f.id} value={f.id}>{f.text}</option>)}
                      </Select>
                    )}
                  </Field>
                )}
                <Field label="Tags" optional error={errors.tags}>{(a) => <TagInput id={a.id} value={tags} onChange={setTags} />}</Field>
              </div>
            </>
          )}

          <div className="flex justify-end gap-2 border-t border-border pt-5">
            <Button variant="ghost" onClick={() => navigate(-1)}>Cancel</Button>
            <Button onClick={submit} loading={create.isPending} disabled={!!cannotPost || upload.isPending}>Publish</Button>
          </div>
        </div>
      </Card>
    </PageColumns>
  );
}
