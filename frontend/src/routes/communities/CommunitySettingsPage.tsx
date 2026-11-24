import { Plus, ShieldAlert, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Button, IconButton, buttonClasses } from '@/components/ui/Button';
import { Avatar, Card, CardHeader } from '@/components/ui/Display';
import { Field, Input, Select, Textarea } from '@/components/ui/Field';
import { ImageUpload } from '@/components/ui/ImageUpload';
import { PageSpinner } from '@/components/ui/Spinner';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { MembersList } from '@/features/communities/components/MembersList';
import { TagInput } from '@/features/communities/components/TagInput';
import {
  useCommunity,
  usePutFlairs,
  usePutRules,
  useUpdateCommunity,
  useUploadCommunityImage,
} from '@/features/communities/hooks';
import { useTopics } from '@/features/onboarding/hooks';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';
import { errorMessage } from '@/services/apiError';
import type { CommunityDetail, Visibility } from '@/types/api';

const onErr = (e: unknown) => toast.error(errorMessage(e));

function DetailsSection({ c }: { c: CommunityDetail }) {
  const topics = useTopics();
  const update = useUpdateCommunity(c.slug);
  const [name, setName] = useState(c.name);
  const [description, setDescription] = useState(c.description ?? '');
  const [topicId, setTopicId] = useState(c.topic?.id ? String(c.topic.id) : '');
  const [visibility, setVisibility] = useState<Visibility>(c.visibility);
  const [tags, setTags] = useState(c.tags);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const save = () => {
    const e: Record<string, string> = {};
    if (name.trim().length < 3) e.name = 'At least 3 characters';
    if (description.trim().length < 10) e.description = 'At least 10 characters';
    setErrors(e);
    if (Object.keys(e).length) return;
    update.mutate(
      { name: name.trim(), description: description.trim(), topicId: topicId ? Number(topicId) : null, visibility, tags },
      { onSuccess: () => toast.success('Community updated'), onError: onErr },
    );
  };

  return (
    <Card>
      <CardHeader title="Details" />
      <div className="space-y-4 p-4">
        <Field label="Display name" error={errors.name}>{(a) => <Input {...a} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Description" error={errors.description}>{(a) => <Textarea {...a} rows={3} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Topic">
            {(a) => (
              <Select {...a} value={topicId} onChange={(e) => setTopicId(e.target.value)}>
                <option value="">No topic</option>
                {topics.data?.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Visibility">
            {(a) => (
              <Select {...a} value={visibility} onChange={(e) => setVisibility(e.target.value as Visibility)}>
                <option value="PUBLIC">Public</option>
                <option value="RESTRICTED">Restricted</option>
                <option value="PRIVATE">Private</option>
              </Select>
            )}
          </Field>
        </div>
        <Field label="Tags">{(a) => <TagInput id={a.id} value={tags} onChange={setTags} />}</Field>
        <div className="flex justify-end"><Button onClick={save} loading={update.isPending}>Save details</Button></div>
      </div>
    </Card>
  );
}

function ImagesSection({ c }: { c: CommunityDetail }) {
  const icon = useUploadCommunityImage(c.slug, 'icon');
  const banner = useUploadCommunityImage(c.slug, 'banner');
  return (
    <Card>
      <CardHeader title="Images" />
      <div className="flex flex-wrap items-center gap-6 p-4">
        <div className="flex items-center gap-3">
          <Avatar src={c.iconUrl} name={c.slug} size="lg" square />
          <ImageUpload label="Upload icon" loading={icon.isPending} onFile={(f) => icon.mutate(f, { onSuccess: () => toast.success('Icon updated'), onError: onErr })} />
        </div>
        <div className="flex flex-1 items-center gap-3">
          <div
            className="h-16 min-w-[160px] flex-1 rounded-md border border-border bg-surface-2"
            style={c.bannerUrl ? { backgroundImage: `url(${c.bannerUrl})`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined}
            aria-label="Current banner"
            role="img"
          />
          <ImageUpload label="Upload banner" maxMb={10} loading={banner.isPending} onFile={(f) => banner.mutate(f, { onSuccess: () => toast.success('Banner updated'), onError: onErr })} />
        </div>
      </div>
    </Card>
  );
}

function RulesSection({ c }: { c: CommunityDetail }) {
  const put = usePutRules(c.slug);
  const [rules, setRules] = useState(c.rules.map((r) => ({ title: r.title, description: r.description ?? '' })));
  useEffect(() => setRules(c.rules.map((r) => ({ title: r.title, description: r.description ?? '' }))), [c.rules]);
  const invalid = rules.some((r) => !r.title.trim());
  return (
    <Card>
      <CardHeader title="Rules" />
      <div className="space-y-2 p-4">
        {rules.map((r, i) => (
          <div key={i} className="flex gap-2 rounded-md border border-border p-3">
            <span className="mt-2 w-5 text-sm font-semibold text-faint">{i + 1}</span>
            <div className="flex-1 space-y-2">
              <Input aria-label={`Rule ${i + 1} title`} value={r.title} onChange={(e) => setRules(rules.map((x, j) => (j === i ? { ...x, title: e.target.value } : x)))} placeholder="Rule" />
              <Textarea aria-label={`Rule ${i + 1} description`} rows={2} className="min-h-[60px]" value={r.description} onChange={(e) => setRules(rules.map((x, j) => (j === i ? { ...x, description: e.target.value } : x)))} placeholder="Details (optional)" />
            </div>
            <IconButton label={`Remove rule ${i + 1}`} size="sm" onClick={() => setRules(rules.filter((_, j) => j !== i))}><Trash2 size={15} /></IconButton>
          </div>
        ))}
        {!rules.length && <p className="text-sm text-muted">No rules yet.</p>}
        <div className="flex justify-between pt-2">
          <Button variant="ghost" size="sm" disabled={rules.length >= 15} onClick={() => setRules([...rules, { title: '', description: '' }])}><Plus size={15} /> Add rule</Button>
          <Button disabled={invalid} loading={put.isPending} onClick={() => put.mutate(rules, { onSuccess: () => toast.success('Rules saved'), onError: onErr })}>Save rules</Button>
        </div>
      </div>
    </Card>
  );
}

function FlairsSection({ c }: { c: CommunityDetail }) {
  const put = usePutFlairs(c.slug);
  const [flairs, setFlairs] = useState<{ id?: number; text: string; color: string }[]>(c.flairs);
  useEffect(() => setFlairs(c.flairs), [c.flairs]);
  return (
    <Card>
      <CardHeader title="Post flairs" />
      <div className="space-y-2 p-4">
        {flairs.map((f, i) => (
          <div key={f.id ?? `n${i}`} className="flex items-center gap-2">
            <input
              type="color"
              aria-label={`Flair ${i + 1} color`}
              value={f.color}
              onChange={(e) => setFlairs(flairs.map((x, j) => (j === i ? { ...x, color: e.target.value } : x)))}
              className="h-10 w-12 cursor-pointer rounded-md border border-border bg-surface-2 p-1"
            />
            <Input aria-label={`Flair ${i + 1} text`} maxLength={32} value={f.text} onChange={(e) => setFlairs(flairs.map((x, j) => (j === i ? { ...x, text: e.target.value } : x)))} placeholder="e.g. Question" />
            <IconButton label={`Remove flair ${i + 1}`} size="sm" onClick={() => setFlairs(flairs.filter((_, j) => j !== i))}><Trash2 size={15} /></IconButton>
          </div>
        ))}
        {!flairs.length && <p className="text-sm text-muted">No flairs yet. Flairs help readers filter and scan posts.</p>}
        <div className="flex justify-between pt-2">
          <Button variant="ghost" size="sm" disabled={flairs.length >= 20} onClick={() => setFlairs([...flairs, { text: '', color: '#3FB68B' }])}><Plus size={15} /> Add flair</Button>
          <Button
            disabled={flairs.some((f) => !f.text.trim())}
            loading={put.isPending}
            onClick={() => put.mutate(flairs.map((f) => ({ ...f, text: f.text.trim() })), { onSuccess: () => toast.success('Flairs saved'), onError: onErr })}
          >
            Save flairs
          </Button>
        </div>
      </div>
    </Card>
  );
}

export default function CommunitySettingsPage() {
  const { slug = '' } = useParams();
  const q = useCommunity(slug);
  useDocumentTitle(`Settings · c/${slug}`);
  if (q.isPending) return <PageSpinner />;
  if (q.isError) return <PageColumns><ErrorState error={q.error} onRetry={() => q.refetch()} /></PageColumns>;
  const c = q.data;
  if (!c.viewer.canModerate) {
    return (
      <PageColumns>
        <EmptyState icon={ShieldAlert} title="Moderators only" description="You don’t have permission to manage this community." action={<Link to={`/c/${slug}`} className={buttonClasses('secondary', 'sm')}>Back to c/{slug}</Link>} />
      </PageColumns>
    );
  }
  return (
    <PageColumns>
      <PageHeader title={`Manage c/${c.slug}`} description="Changes apply immediately for everyone." actions={<Link to={`/c/${c.slug}`} className={buttonClasses('outline', 'sm')}>View community</Link>} />
      <div className="space-y-4 px-4 sm:px-0">
        <DetailsSection c={c} />
        <ImagesSection c={c} />
        <RulesSection c={c} />
        <FlairsSection c={c} />
        <Card>
          <CardHeader title="Members & roles" />
          <MembersList slug={c.slug} canManageRoles={c.viewer.role === 'OWNER' || c.viewer.role === 'ADMIN'} />
        </Card>
      </div>
    </PageColumns>
  );
}
