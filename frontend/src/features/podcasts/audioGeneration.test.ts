import { describe, expect, it } from 'vitest';
import type { AudioJob } from '@/types/podcasts';
import { aiVoiceDescription, estimateMinutes, formatChars, isJobActive, jobProgress, providerName } from './audioGeneration';

const job = (p: Partial<AudioJob>): AudioJob => ({
  id: 1, status: 'GENERATING', provider: 'elevenlabs', model: 'eleven_multilingual_v2', characters: 1000, charactersSent: 0,
  chunksTotal: 4, chunksDone: 0, durationSeconds: null, error: null, cancelRequested: false,
  createdAt: '2026-01-01T00:00:00Z', startedAt: null, finishedAt: null, ...p,
});

describe('audio generation helpers', () => {
  it('tracks active jobs and progress', () => {
    expect(isJobActive(null)).toBe(false);
    expect(isJobActive(job({ status: 'QUEUED' }))).toBe(true);
    expect(isJobActive(job({ status: 'CANCELLED' }))).toBe(false);
    expect(jobProgress(job({ chunksDone: 1 }))).toBe(0.25);
    expect(jobProgress(job({ status: 'READY', chunksDone: 0 }))).toBe(1);
    expect(jobProgress(job({ chunksTotal: 0 }))).toBe(0);
    expect(jobProgress(job({ chunksDone: 9 }))).toBe(1);
  });

  it('names the provider and model in the disclosure', () => {
    expect(providerName('elevenlabs')).toBe('ElevenLabs');
    expect(providerName('other')).toBe('other');
    expect(aiVoiceDescription('elevenlabs', 'eleven_multilingual_v2')).toContain('AI-generated with ElevenLabs (eleven_multilingual_v2)');
    expect(aiVoiceDescription(null, null)).toContain('a text-to-speech service');
    expect(aiVoiceDescription(null, null)).not.toContain('()');
  });

  it('formats estimates', () => {
    expect(estimateMinutes(0)).toBe(0);
    expect(estimateMinutes(100)).toBe(1);
    expect(estimateMinutes(45_000)).toBe(50);
    expect(formatChars(12345)).toBe('12,345');
  });
});
