import { Mic, Plus, ShieldAlert } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Button, buttonClasses } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Skeleton } from '@/components/ui/Display';
import { Input, Select, Textarea } from '@/components/ui/Field';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { podcastApi, useStudio } from '@/features/podcasts/api';
import { EpisodeRow } from '@/features/podcasts/components/EpisodeCard';
import { EPISODE_TYPES, STATUS_LABEL } from '@/features/podcasts/helpers';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { ApiError, errorMessage } from '@/services/apiError';
import type { EpisodeStatus, EpisodeType } from '@/types/podcasts';

const ORDER: EpisodeStatus[] = ['IN_REVIEW', 'DRAFT', 'PUBLISHED', 'ARCHIVED'];

/** A community's podcast studio: every episode by status, and "New episode". Moderators only. */
export default function StudioPage() {
  const { slug = '' } = useParams();
  useDocumentTitle(`Podcast studio · c/${slug}`);
  const navigate = useNavigate();
  const q = useStudio(slug);
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState('');
  const [summary, setSummary] = useState('');
  const [type, setType] = useState<EpisodeType>('DEEP_DIVE');
  const [busy, setBusy] = useState(false);

  if (q.isError && q.error instanceof ApiError && q.error.status === 403) {
    return <EmptyState icon={ShieldAlert} title="Moderators only" description={`Only moderators of c/${slug} can use its podcast studio.`} action={<Link to={`/c/${slug}`} className={buttonClasses('secondary', 'sm')}>Back to c/{slug}</Link>} />;
  }

  const create = async () => {
    setBusy(true);
    try {
      const e = await podcastApi.create(slug, { title: title.trim(), summary: summary.trim() || undefined, type });
      toast.success('Draft created');
      navigate(`/c/${slug}/podcasts/studio/${e.id}`);
    } catch (err) {
      toast.error(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const groups = ORDER.map((s) => ({ status: s, items: (q.data ?? []).filter((e) => e.status === s) })).filter((g) => g.items.length);

  return (
    <div className="mx-auto w-full max-w-[900px] px-3 py-5 sm:px-6">
      <Link to={`/c/${slug}`} className="text-xs text-muted hover:text-text">← c/{slug}</Link>
      <div className="mt-1 mb-5 flex flex-wrap items-end gap-3">
        <div className="mr-auto">
          <h1 className="flex items-center gap-2 text-2xl font-bold"><Mic size={22} className="text-brand" /> Podcast studio</h1>
          <p className="mt-1 text-sm text-muted">Draft, review and publish c/{slug}'s episodes. Every episode needs real uploaded media and a transcript before it can go live.</p>
        </div>
        <Button onClick={() => setOpen(true)}><Plus size={16} /> New episode</Button>
      </div>

      {q.isPending && <div className="space-y-2">{Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-24" />)}</div>}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && q.data.length === 0 && (
        <EmptyState icon={Mic} title="No episodes yet" description="The mic is warm. Start a draft, upload your recording and transcript, then submit it for review." mascot="headphones" action={<Button size="sm" onClick={() => setOpen(true)}><Plus size={15} /> New episode</Button>} />
      )}
      {groups.map((g) => (
        <section key={g.status} className="mb-6">
          <h2 className="mb-2 text-xs font-bold uppercase tracking-wider text-muted">{STATUS_LABEL[g.status]} ({g.items.length})</h2>
          <div className="space-y-1">{g.items.map((e) => <EpisodeRow key={e.id} e={e} studio />)}</div>
        </section>
      ))}

      <Dialog
        open={open}
        onOpenChange={setOpen}
        title="New episode"
        description="Creates a private draft. Nothing is published until a moderator publishes it."
        footer={<><Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button><Button loading={busy} disabled={!title.trim()} onClick={create}>Create draft</Button></>}
      >
        <div className="space-y-3">
          <label className="block text-sm font-semibold">Title<Input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} className="mt-1" placeholder="Rust vs Go: a community debate" /></label>
          <label className="block text-sm font-semibold">Type
            <Select value={type} onChange={(e) => setType(e.target.value as EpisodeType)} className="mt-1">
              {EPISODE_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </Select>
          </label>
          <label className="block text-sm font-semibold">Summary <span className="font-normal text-muted">(optional)</span><Textarea value={summary} onChange={(e) => setSummary(e.target.value)} maxLength={2000} rows={3} className="mt-1" /></label>
        </div>
      </Dialog>
    </div>
  );
}
