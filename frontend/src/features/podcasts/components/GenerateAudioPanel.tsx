import { useMutation, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, AudioWaveform, Loader2, X } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { Button } from '@/components/ui/Button';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { EpisodeDetail } from '@/types/podcasts';
import { timeAgo, timeUntil } from '@/utils/format';
import { podcastApi, podcastKeys, useAudioGeneration } from '../api';
import { estimateMinutes, formatChars, isJobActive, jobProgress, JOB_STATUS_LABEL } from '../audioGeneration';
import { formatDuration } from '../helpers';
import { Panel } from './StudioPanels';

/**
 * "Generate audio": reads the saved two-host script aloud with the server's TTS provider (D-036).
 * Only rendered when capabilities.audioGeneration is true. Shows voices, the character estimate and the community's
 * daily budget before starting, then progress with cancel. The result is always labelled as an AI-generated voice.
 */
export function GenerateAudioPanel({ e, locked }: { e: EpisodeDetail; locked: boolean }) {
  const qc = useQueryClient();
  const q = useAudioGeneration(e.id, true);
  const g = q.data;
  const job = g?.job ?? null;
  const active = isJobActive(job);
  const refresh = () => qc.invalidateQueries({ queryKey: podcastKeys.audio(e.id) });
  const start = useMutation({ mutationFn: () => podcastApi.generateAudio(e.id), onSettled: refresh });
  const cancel = useMutation({ mutationFn: () => podcastApi.cancelAudio(e.id), onSettled: refresh });

  // When a job finishes, the episode has new media, transcript and chapters: refetch them.
  const lastStatus = useRef(job?.status);
  useEffect(() => {
    const prev = lastStatus.current;
    lastStatus.current = job?.status;
    if (prev && prev !== job?.status && job?.status === 'READY') {
      qc.invalidateQueries({ queryKey: podcastKeys.episode(e.id) });
      qc.invalidateQueries({ queryKey: ['podcasts', 'transcript', e.id] });
      qc.invalidateQueries({ queryKey: ['podcasts', 'studio'] });
      toast.success('Audio generated. Review it before publishing');
    } else if (prev && prev !== job?.status && job?.status === 'FAILED') {
      toast.error('Audio generation failed');
    }
  }, [job?.status, e.id, qc]);

  const blockers = (g?.blockers ?? []).filter((b) => !(active && b.startsWith('Audio is already')));
  const pct = Math.round(jobProgress(job) * 100);

  const onStart = () => {
    if (!g) return;
    const replacing = e.mediaUrl ? ' It replaces the current media, transcript and chapters.' : '';
    if (!window.confirm(`Generate audio from the saved script? This sends about ${formatChars(g.estimate.characters)} characters to ${g.providerLabel ?? 'the TTS provider'}.${replacing}`)) return;
    start.mutate(undefined, { onSuccess: () => toast.info('Generating audio… you can leave this page'), onError: (er) => toast.error(errorMessage(er)) });
  };

  return (
    <Panel
      title="Generate audio"
      action={<span className="inline-flex items-center gap-1 rounded-full bg-warning/10 px-2 py-0.5 text-[11px] font-bold text-warning"><AudioWaveform size={12} aria-hidden="true" /> AI-generated voice</span>}
    >
      <p className="-mt-1 mb-3 text-xs text-muted">
        Reads the saved script&apos;s <strong>HOST A</strong> and <strong>HOST B</strong> lines aloud with two stock AI voices, then builds a
        timed transcript and chapters from it. Listeners always see an &ldquo;AI-generated voice&rdquo; label on this episode. No voice is cloned.
      </p>

      {q.isPending && <p className="text-sm text-muted"><Loader2 size={14} className="mr-1 inline animate-spin" /> Checking the script…</p>}
      {q.isError && <p className="text-sm text-danger">{errorMessage(q.error)}</p>}

      {g && (
        <div className="space-y-3">
          {g.voices.length > 0 && (
            <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
              {g.voices.map((v) => (
                <div key={v.speaker} className="contents">
                  <dt className="font-semibold">{v.speaker === 'HOST A' ? 'Host A' : 'Host B'}</dt>
                  <dd className="min-w-0 truncate text-muted">{v.name} <span className="font-mono text-[11px] text-faint">{v.voiceId}</span></dd>
                </div>
              ))}
            </dl>
          )}

          <div className="rounded-xl bg-surface-2/60 px-3 py-2 text-sm">
            <p>
              <span className="font-semibold tabular-nums">{formatChars(g.estimate.characters)}</span> characters
              {g.estimate.characters > 0 && <> · {g.estimate.chunks} request{g.estimate.chunks === 1 ? '' : 's'} · about {estimateMinutes(g.estimate.characters)} min of speech</>}
              {g.estimate.sections > 0 && <> · {g.estimate.sections} chapter{g.estimate.sections === 1 ? '' : 's'}</>}
            </p>
            <p className="mt-0.5 text-xs text-muted">
              Estimated from the saved script ({g.estimate.turns} turns). Save script edits first. {g.providerLabel}
              {g.model ? ` · ${g.model}` : ''}
            </p>
            <p className="mt-1 text-xs text-muted">
              Community budget: <span className="tabular-nums">{formatChars(g.budget.remaining)}</span> of {formatChars(g.budget.dailyLimit)} characters left today
              (resets in {timeUntil(g.budget.resetsAt)}, midnight UTC).
            </p>
          </div>

          {!active && blockers.length > 0 && (
            <ul className="space-y-1 rounded-xl border border-warning/30 bg-warning/10 px-3 py-2 text-xs">
              {blockers.map((b) => <li key={b} className="flex gap-1.5"><AlertTriangle size={13} className="mt-px shrink-0 text-warning" /> {b}</li>)}
            </ul>
          )}

          {active && job && (
            <div role="status" aria-live="polite">
              <div className="flex items-center justify-between gap-2 text-xs text-muted">
                <span className="inline-flex items-center gap-1.5">
                  <Loader2 size={13} className="animate-spin text-brand" />
                  {job.cancelRequested ? 'Cancelling…' : job.status === 'QUEUED' ? 'Queued…' : `Generating… ${job.chunksDone} of ${job.chunksTotal} parts`}
                </span>
                <button
                  type="button"
                  disabled={cancel.isPending || job.cancelRequested}
                  onClick={() => cancel.mutate(undefined, { onSuccess: () => toast.info('Cancelling audio generation'), onError: (er) => toast.error(errorMessage(er)) })}
                  className="inline-flex items-center gap-1 font-semibold hover:text-text disabled:opacity-50"
                >
                  <X size={12} /> Cancel
                </button>
              </div>
              <div className="mt-1 h-2 overflow-hidden rounded-full bg-surface-3" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label="Audio generation progress">
                <div className="h-full bg-brand transition-[width]" style={{ width: `${pct}%` }} />
              </div>
            </div>
          )}

          {!active && job?.status === 'FAILED' && job.error && (
            <div className="flex gap-2 rounded-xl border border-danger/30 bg-danger/10 px-3 py-2 text-sm"><AlertTriangle size={15} className="mt-0.5 shrink-0 text-danger" /> {job.error}</div>
          )}
          {!active && job && job.status !== 'FAILED' && (
            <p className="text-xs text-muted">
              Last run: {JOB_STATUS_LABEL[job.status]}
              {job.status === 'READY' && job.durationSeconds != null && <> · {formatDuration(Math.round(job.durationSeconds))} of audio</>}
              {job.status === 'CANCELLED' && <> · {formatChars(job.charactersSent)} characters were sent before cancelling</>}
              {job.finishedAt && <> · {timeAgo(job.finishedAt) === 'just now' ? 'just now' : `${timeAgo(job.finishedAt)} ago`}</>}
            </p>
          )}

          {!active && (
            <Button onClick={onStart} loading={start.isPending} disabled={blockers.length > 0 || locked || !g.available}>
              <AudioWaveform size={15} /> {e.audioOrigin === 'AI_TTS' ? 'Regenerate audio' : 'Generate audio'}
            </Button>
          )}
        </div>
      )}
    </Panel>
  );
}
