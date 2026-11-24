import { zodResolver } from '@hookform/resolvers/zod';
import { Globe, Lock, Plus, ShieldCheck, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Controller, useFieldArray, useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { Button, IconButton } from '@/components/ui/Button';
import { Card } from '@/components/ui/Display';
import { Field, FormAlert, Input, Select, Textarea } from '@/components/ui/Field';
import { toast } from '@/components/ui/Toast';
import { TagInput } from '@/features/communities/components/TagInput';
import { useCreateCommunity } from '@/features/communities/hooks';
import { useTopics } from '@/features/onboarding/hooks';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';
import type { Visibility } from '@/types/api';
import { cn } from '@/utils/format';
import { applyServerErrors } from '@/utils/forms';
import { communitySlugSchema } from '@/utils/validation';

const schema = z.object({
  slug: communitySlugSchema,
  name: z.string().trim().min(3, 'At least 3 characters').max(60, 'At most 60 characters'),
  description: z.string().trim().min(10, 'Tell people what this community is about (10+ characters)').max(500),
  topicId: z.string().optional(),
  visibility: z.enum(['PUBLIC', 'RESTRICTED', 'PRIVATE']),
  tags: z.array(z.string()).max(5),
  rules: z.array(z.object({ title: z.string().trim().min(1, 'Required').max(100), description: z.string().max(500) })).max(15),
});
type Values = z.infer<typeof schema>;

const VIS: { value: Visibility; label: string; desc: string; icon: typeof Globe }[] = [
  { value: 'PUBLIC', label: 'Public', desc: 'Anyone can view and post.', icon: Globe },
  { value: 'RESTRICTED', label: 'Restricted', desc: 'Anyone can view; approved contributors post.', icon: ShieldCheck },
  { value: 'PRIVATE', label: 'Private', desc: 'Only members can view and post.', icon: Lock },
];

export default function CreateCommunityPage() {
  useDocumentTitle('Create community');
  const navigate = useNavigate();
  const topics = useTopics();
  const create = useCreateCommunity();
  const [formError, setFormError] = useState<string | null>(null);
  const { register, handleSubmit, control, setError, watch, formState } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { slug: '', name: '', description: '', visibility: 'PUBLIC', tags: [], rules: [{ title: 'Be respectful', description: 'Discuss ideas, not people.' }] },
  });
  const rules = useFieldArray({ control, name: 'rules' });
  const slug = watch('slug');

  const onSubmit = handleSubmit((v) => {
    setFormError(null);
    create.mutate(
      {
        slug: v.slug,
        name: v.name,
        description: v.description,
        topicId: v.topicId ? Number(v.topicId) : null,
        visibility: v.visibility,
        tags: v.tags,
        rules: v.rules,
      },
      {
        onSuccess: (c) => {
          toast.success(`c/${c.slug} is live`);
          navigate(`/c/${c.slug}`);
        },
        onError: (e) => {
          const msg = applyServerErrors(e, setError, ['slug', 'name', 'description', 'topicId', 'visibility', 'tags']);
          if (msg && /slug|taken|exists/i.test(msg)) setError('slug', { type: 'server', message: msg });
          else setFormError(msg);
        },
      },
    );
  });

  return (
    <PageColumns>
      <PageHeader title="Create a community" description="Give it a clear purpose. Good rules make good discussions." />
      <Card className="mx-4 p-5 sm:mx-0 sm:p-6">
        <form onSubmit={onSubmit} noValidate className="space-y-5">
          <FormAlert message={formError} />
          <div className="grid gap-5 sm:grid-cols-2">
            <Field label="Address" error={formState.errors.slug?.message} hint={`howlers/c/${slug || 'your_community'} · can’t be changed later`}>
              {(a) => <Input {...a} {...register('slug')} placeholder="system_design" autoComplete="off" />}
            </Field>
            <Field label="Display name" error={formState.errors.name?.message}>
              {(a) => <Input {...a} {...register('name')} placeholder="System Design" />}
            </Field>
          </div>
          <Field label="Description" error={formState.errors.description?.message}>
            {(a) => <Textarea {...a} {...register('description')} rows={3} placeholder="What is discussed here, and who is it for?" />}
          </Field>
          <div className="grid gap-5 sm:grid-cols-2">
            <Field label="Topic" optional error={formState.errors.topicId?.message}>
              {(a) => (
                <Select {...a} {...register('topicId')}>
                  <option value="">No topic</option>
                  {topics.data?.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
                </Select>
              )}
            </Field>
            <Field label="Tags" optional error={formState.errors.tags?.message}>
              {(a) => (
                <Controller
                  control={control}
                  name="tags"
                  render={({ field }) => <TagInput id={a.id} describedBy={a['aria-describedby']} value={field.value} onChange={field.onChange} />}
                />
              )}
            </Field>
          </div>

          <fieldset>
            <legend className="mb-2 text-sm font-medium">Visibility</legend>
            <Controller
              control={control}
              name="visibility"
              render={({ field }) => (
                <div className="grid gap-2 sm:grid-cols-3">
                  {VIS.map(({ value, label, desc, icon: Icon }) => (
                    <label
                      key={value}
                      className={cn(
                        'flex cursor-pointer flex-col gap-1 rounded-md border p-3 transition-colors',
                        field.value === value ? 'border-accent bg-accent/10' : 'border-border hover:bg-surface-2',
                      )}
                    >
                      <input type="radio" className="sr-only" checked={field.value === value} onChange={() => field.onChange(value)} />
                      <span className="flex items-center gap-2 text-sm font-medium"><Icon size={15} /> {label}</span>
                      <span className="text-xs text-muted">{desc}</span>
                    </label>
                  ))}
                </div>
              )}
            />
          </fieldset>

          <fieldset>
            <legend className="mb-2 text-sm font-medium">Rules</legend>
            <div className="space-y-2">
              {rules.fields.map((f, i) => (
                <div key={f.id} className="flex gap-2 rounded-md border border-border p-3">
                  <span className="mt-2 w-5 shrink-0 text-sm font-semibold text-faint">{i + 1}</span>
                  <div className="flex-1 space-y-2">
                    <Input aria-label={`Rule ${i + 1} title`} {...register(`rules.${i}.title`)} placeholder="Rule" aria-invalid={!!formState.errors.rules?.[i]?.title} />
                    <Textarea aria-label={`Rule ${i + 1} description`} {...register(`rules.${i}.description`)} rows={2} className="min-h-[60px]" placeholder="What it means in practice (optional)" />
                    {formState.errors.rules?.[i]?.title && <p className="text-xs text-danger">{formState.errors.rules[i]?.title?.message}</p>}
                  </div>
                  <IconButton label={`Remove rule ${i + 1}`} size="sm" onClick={() => rules.remove(i)}>
                    <Trash2 size={15} />
                  </IconButton>
                </div>
              ))}
            </div>
            {rules.fields.length < 15 && (
              <Button variant="ghost" size="sm" className="mt-2" onClick={() => rules.append({ title: '', description: '' })}>
                <Plus size={15} /> Add rule
              </Button>
            )}
          </fieldset>

          <div className="flex justify-end gap-2 border-t border-border pt-5">
            <Button variant="ghost" onClick={() => navigate(-1)}>Cancel</Button>
            <Button type="submit" loading={create.isPending}>Create community</Button>
          </div>
        </form>
      </Card>
    </PageColumns>
  );
}
