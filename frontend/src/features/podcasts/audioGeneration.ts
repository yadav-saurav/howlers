import type { AudioJob, AudioJobStatus } from '@/types/podcasts';

/** Pure helpers for generated (text-to-speech) podcast audio. */

const PROVIDERS: Record<string, string> = { elevenlabs: 'ElevenLabs' };

export const providerName = (id: string | null | undefined) => (id ? (PROVIDERS[id] ?? id) : 'a text-to-speech service');

export const isJobActive = (job: AudioJob | null | undefined) => !!job && (job.status === 'QUEUED' || job.status === 'GENERATING');

/** 0..1, from chunks synthesised so far. Finished jobs report 1. */
export function jobProgress(job: AudioJob | null | undefined): number {
  if (!job) return 0;
  if (job.status === 'READY') return 1;
  if (job.chunksTotal <= 0) return 0;
  return Math.min(1, Math.max(0, job.chunksDone / job.chunksTotal));
}

export const JOB_STATUS_LABEL: Record<AudioJobStatus, string> = {
  QUEUED: 'Queued',
  GENERATING: 'Generating',
  READY: 'Ready',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

/** The persistent disclosure shown wherever generated audio plays. */
export function aiVoiceDescription(provider: string | null | undefined, model: string | null | undefined): string {
  const by = providerName(provider);
  return `The voices in this episode are AI-generated with ${by}${model ? ` (${model})` : ''} from a reviewed script. They are not recordings of real people.`;
}

/** Rough spoken length: about 15 characters per second of speech. */
export function estimateMinutes(characters: number): number {
  return Math.max(characters > 0 ? 1 : 0, Math.round(characters / 15 / 60));
}

export const formatChars = (n: number) => n.toLocaleString('en-US');
