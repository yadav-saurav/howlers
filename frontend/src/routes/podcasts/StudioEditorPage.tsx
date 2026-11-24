import { Archive, Check, Circle, ExternalLink, Send, ShieldAlert, Trash2, Upload } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Button, buttonClasses } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Skeleton } from '@/components/ui/Display';
import { Input, Select, Textarea } from '@/components/ui/Field';
import { Switch } from '@/components/ui/Switch';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { useQueryClient } from '@tanstack/react-query';
import { podcastApi, useCapabilities, useEpisode, useEpisodeMutation } from '@/features/podcasts/api';
import { EPISODE_TYPES, STATUS_LABEL } from '@/features/podcasts/helpers';
import { AiVoiceBadge } from '@/features/podcasts/components/AiVoiceBadge';
import { GenerateAudioPanel } from '@/features/podcasts/components/GenerateAudioPanel';
import { aiVoiceDescription } from '@/features/podcasts/audioGeneration';
import { ChaptersPanel, MediaPanel, Panel, ScriptPanel, SourcesPanel, ThumbnailPanel, TranscriptPanelEditor } from '@/features/podcasts/components/StudioPanels';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { ApiError, errorMessage } from '@/services/apiError';
import type { EpisodeDetail, EpisodeStatus, EpisodeType } from '@/types/podcasts';
import { cn } from '@/utils/format';

const STEPS: EpisodeStatus[] = ['DRAFT', 'IN_REVIEW', 'PUBLISHED'];

function DetailsPanel({ e }: { e: EpisodeDetail }) {
  const [title, setTitle] = useState(e.title);
  const [summary, setSummary] = useState(e.summary ?? '');
  const [type, setType] = useState<EpisodeType>(e.type);
  const m = useEpisodeMutation(e.id, () => podcastApi.update(e.id, { title: title.trim(), summary: summary.trim(), type }));
  const dl = useEpisodeMutation(e.id, (v: boolean) => podcastApi.update(e.id, { allowDownload: v }));
  const dirty = title !== e.title || summary !== (e.summary ?? '') || type !== e.type;
  return (
    <Panel title="Details">
      <div className="space-y-3">
        <label className="block text-sm font-semibold">Title<Input value={title} onChange={(ev) => setTitle(ev.target.value)} maxLength={200} className="mt-1" /></label>
        <label className="block text-sm font-semibold">Type
          <Select value={type} onChange={(ev) => setType(ev.target.value as EpisodeType)} className="mt-1">
            {EPISODE_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </Select>
        </label>
        <label className="block text-sm font-semibold">Summary<Textarea value={summary} onChange={(ev) => setSummary(ev.target.value)} maxLength={2000} rows={4} className="mt-1" /></label>
        <div className="rounded-xl bg-surface-2/60 px-3">
          <Switch label="Allow downloads" description="Listeners can save the media file." checked={e.allowDownload} onCheckedChange={(v) => dl.mutate(v, { onError: (er) => toast.error(errorMessage(er)) })} />
        </div>
        <Button size="sm" loading={m.isPending} disabled={!dirty || !title.trim()} onClick={() => m.mutate(undefined, { onSuccess: () => toast.success('Details saved'), onError: (er) => toast.error(errorMessage(er)) })}>Save details</Button>
      </div>
    </Panel>
  );
}

function Checklist({ e }: { e: EpisodeDetail }) {
  const items = [
    { ok: !!e.mediaUrl, label: 'Media uploaded', required: true },
    { ok: e.hasTranscript, label: 'Transcript added', required: true },
    { ok: e.chapters.length > 0, label: 'Chapters', required: false },
    { ok: e.sources.length > 0, label: 'Sources linked', required: false },
  ];
  return (
    <ul className="space-y-1 text-sm">
      {items.map((i) => (
        <li key={i.label} className="flex items-center gap-2">
          {i.ok ? <Check size={15} className="text-accent" /> : <Circle size={15} className={i.required ? 'text-warning' : 'text-faint'} />}
          <span className={cn(!i.ok && 'text-muted')}>{i.label}</span>
          {!i.required && <span className="text-xs text-faint">(optional)</span>}
        </li>
      ))}
    </ul>
  );
}

/** Studio editor for one episode: details, media, transcript, chapters, sources, script, and the review workflow. */
export default function StudioEditorPage() {
  const { slug = '', id: raw = '' } = useParams();
  const id = Number(raw);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const q = useEpisode(id, { poll: true });
  const caps = useCapabilities();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const submit = useEpisodeMutation(id, () => podcastApi.submit(id));
  const publish = useEpisodeMutation(id, () => podcastApi.publish(id));
  const unpublish = useEpisodeMutation(id, () => podcastApi.unpublish(id));
  const [deleting, setDeleting] = useState(false);
  useDocumentTitle(q.data ? `Studio · ${q.data.title}` : 'Studio');

  if (q.isPending) return <div className="mx-auto max-w-[1000px] space-y-3 px-3 py-6 sm:px-6"><Skeleton className="h-10 w-1/2" /><Skeleton className="h-64 rounded-2xl" /></div>;
  if (q.isError) {
    const st = q.error instanceof ApiError ? q.error.status : 0;
    return st === 403 || st === 404 ? (
      <EmptyState icon={ShieldAlert} title="You can't edit this episode" description="Only moderators of its community can open it in the studio." action={<Link to={`/c/${slug}`} className={buttonClasses('secondary', 'sm')}>Back</Link>} />
    ) : (
      <div className="p-6"><ErrorState error={q.error} onRetry={() => q.refetch()} /></div>
    );
  }
  const e = q.data;
  if (!e.viewer.canManage) {
    return <EmptyState icon={ShieldAlert} title="Moderators only" description="You can listen to this episode, but not edit it." action={<Link to={`/podcasts/${e.id}`} className={buttonClasses('secondary', 'sm')}>Open episode</Link>} />;
  }

  const locked = e.status === 'PUBLISHED';
  const ready = !!e.mediaUrl && e.hasTranscript;
  const stepIndex = e.status === 'ARCHIVED' ? -1 : STEPS.indexOf(e.status);
  const onDone = (msg: string) => ({ onSuccess: () => toast.success(msg), onError: (er: unknown) => toast.error(errorMessage(er)) });

  return (
    <div className="mx-auto max-w-[1000px] px-3 py-5 sm:px-6">
      <Link to={`/c/${e.community.slug}/podcasts/studio`} className="text-xs text-muted hover:text-text">← Studio · c/{e.community.slug}</Link>
      <div className="mt-1 flex flex-wrap items-center gap-3">
        <h1 className="mr-auto min-w-0 break-words text-2xl font-bold">{e.title}</h1>
        <Link to={`/podcasts/${e.id}`} className="inline-flex h-8 items-center gap-1.5 rounded-full bg-surface-2 px-3 text-xs font-semibold hover:bg-surface-3"><ExternalLink size={13} /> View episode</Link>
      </div>

      {/* Workflow */}
      <section className="mt-4 rounded-2xl border border-border p-4">
        <ol className="flex flex-wrap items-center gap-2 text-sm" aria-label="Publishing workflow">
          {STEPS.map((s, i) => (
            <li key={s} className="flex items-center gap-2">
              <span className={cn('rounded-full px-3 py-1 font-semibold', i === stepIndex ? 'bg-brand text-brand-fg' : i < stepIndex ? 'bg-accent/15 text-accent' : 'bg-surface-2 text-muted')} aria-current={i === stepIndex ? 'step' : undefined}>
                {STATUS_LABEL[s]}
              </span>
              {i < STEPS.length - 1 && <span className="text-faint" aria-hidden="true">→</span>}
            </li>
          ))}
          {e.status === 'ARCHIVED' && <li className="rounded-full bg-surface-2 px-3 py-1 font-semibold text-muted">Archived</li>}
        </ol>
        <div className="mt-4 grid gap-4 sm:grid-cols-[1fr_auto]">
          <Checklist e={e} />
          <div className="flex flex-wrap items-start gap-2">
            {(e.status === 'DRAFT' || e.status === 'ARCHIVED') && (
              <Button variant="secondary" loading={submit.isPending} onClick={() => submit.mutate(undefined, onDone('Submitted for review'))}><Send size={15} /> Submit for review</Button>
            )}
            {e.status === 'IN_REVIEW' && (
              <Button loading={publish.isPending} disabled={!ready} title={ready ? undefined : 'Upload media and a transcript first'} onClick={() => publish.mutate(undefined, onDone('Episode published'))}><Upload size={15} /> Publish</Button>
            )}
            {e.status === 'PUBLISHED' && (
              <Button variant="secondary" loading={unpublish.isPending} onClick={() => unpublish.mutate(undefined, onDone('Episode unpublished'))}><Archive size={15} /> Unpublish</Button>
            )}
            <Button variant="ghost" className="text-danger" onClick={() => setConfirmDelete(true)}><Trash2 size={15} /> Delete</Button>
          </div>
        </div>
        {e.status === 'IN_REVIEW' && !ready && <p className="mt-3 text-xs text-warning">Publishing needs uploaded media and a transcript.</p>}
        {e.audioOrigin === 'AI_TTS' && (
          <div className="mt-3 flex flex-wrap items-start gap-2 rounded-xl border border-warning/30 bg-warning/10 px-3 py-2 text-xs">
            <AiVoiceBadge provider={e.audioProvider} model={e.audioModel} />
            <span className="min-w-0 flex-1">
              {aiVoiceDescription(e.audioProvider, e.audioModel)} Listen to it in full before publishing: the episode page, player and API
              show this label to everyone.
            </span>
          </div>
        )}
        {e.publishedBy && e.status === 'PUBLISHED' && <p className="mt-3 text-xs text-muted">Published by u/{e.publishedBy.username}.</p>}
      </section>

      <div className="mt-5 grid gap-5 lg:grid-cols-2">
        <div className="space-y-5">
          <DetailsPanel e={e} />
          <ThumbnailPanel e={e} />
          <ChaptersPanel key={`ch-${e.chapters.length}`} e={e} />
        </div>
        <div className="space-y-5">
          <MediaPanel e={e} caps={caps.data} locked={locked} />
          {caps.data?.audioGeneration && <GenerateAudioPanel e={e} locked={locked} />}
          <TranscriptPanelEditor e={e} locked={locked} />
          <SourcesPanel e={e} />
        </div>
      </div>
      <div className="mt-5">
        <ScriptPanel e={e} caps={caps.data} />
      </div>

      <Dialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        size="sm"
        title="Delete this episode?"
        description="The episode, its media, transcript and chapters are permanently deleted. This can't be undone."
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmDelete(false)}>Cancel</Button>
            <Button
              variant="danger"
              loading={deleting}
              onClick={async () => {
                setDeleting(true);
                try {
                  await podcastApi.remove(e.id);
                  qc.invalidateQueries({ queryKey: ['podcasts'] });
                  toast.success('Episode deleted');
                  navigate(`/c/${e.community.slug}/podcasts/studio`);
                } catch (er) {
                  toast.error(errorMessage(er));
                  setDeleting(false);
                }
              }}
            >
              Delete
            </Button>
          </>
        }
      >
        <p className="truncate text-sm text-muted">“{e.title}”</p>
      </Dialog>
    </div>
  );
}
