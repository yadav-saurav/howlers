import type { CommunityRef, UserSummary } from './api';

/** Mirrors docs/API.md → "Podcasts (Phase 6)". */
export type EpisodeType =
  | 'WEEKLY_ROUNDUP'
  | 'DEEP_DIVE'
  | 'DEBATE'
  | 'INTERVIEW'
  | 'BEGINNER_GUIDE'
  | 'MONTHLY_REPORT'
  | 'COMPARISON'
  | 'HIGHLIGHTS'
  | 'MULTI_PERSPECTIVE';

export type EpisodeStatus = 'DRAFT' | 'IN_REVIEW' | 'PUBLISHED' | 'ARCHIVED';
export type MediaKind = 'AUDIO' | 'VIDEO';
export type AudioOrigin = 'UPLOADED' | 'AI_TTS';
export type ScriptStatus = 'NONE' | 'GENERATING' | 'READY' | 'FAILED';

export interface EpisodeProgress {
  positionSeconds: number;
  completed: boolean;
}

export interface EpisodeSummary {
  id: number;
  community: CommunityRef;
  title: string;
  summary: string;
  type: EpisodeType;
  mediaKind: MediaKind | null;
  durationSeconds: number | null;
  thumbnailUrl: string | null;
  status: EpisodeStatus;
  publishedAt: string | null;
  createdAt: string;
  playCount: number;
  saved: boolean;
  progress: EpisodeProgress | null;
  chapterCount: number;
  /** Where the media came from (null until there is media). AI_TTS = generated voice, always labelled. */
  audioOrigin: AudioOrigin | null;
}

export interface Chapter {
  id: number;
  startSeconds: number;
  title: string;
}

export interface EpisodeSource {
  position: number;
  postId: number;
  commentId: number | null;
  title: string;
  communitySlug: string;
  excerpt: string;
  link: string;
}

export interface EpisodeDetail extends EpisodeSummary {
  mediaUrl: string | null;
  mediaContentType: string | null;
  allowDownload: boolean;
  chapters: Chapter[];
  sources: EpisodeSource[];
  hasTranscript: boolean;
  transcriptVttUrl: string | null;
  script: string | null;
  scriptStatus: ScriptStatus;
  scriptSource: 'HUMAN' | 'AI_DRAFT' | null;
  scriptError: string | null;
  createdBy: UserSummary | null;
  publishedBy: UserSummary | null;
  viewer: { canManage: boolean };
  /** Set only for AI_TTS audio, e.g. "elevenlabs" and "eleven_multilingual_v2". */
  audioProvider: string | null;
  audioModel: string | null;
}

export interface TranscriptSegment {
  id: number;
  startSeconds: number;
  endSeconds: number;
  speaker: string | null;
  text: string;
}

export interface PodcastCapabilities {
  aiScriptDrafting: boolean;
  audioGeneration: boolean;
  videoGeneration: false;
  maxUploadMb: number;
  audioTypes: string[];
  videoTypes: string[];
}

export interface PodcastHome {
  featured: EpisodeSummary[];
  continueListening: EpisodeSummary[];
  recent: EpisodeSummary[];
  saved: EpisodeSummary[];
}

export interface SourceRef {
  postId: number;
  commentId?: number;
}

export interface EpisodePatch {
  title?: string;
  summary?: string;
  type?: EpisodeType;
  allowDownload?: boolean;
  script?: string;
  chapters?: { startSeconds: number; title: string }[];
  sources?: SourceRef[];
}

export type TranscriptFormat = 'vtt' | 'srt' | 'timestamped';

/** Audio generation from the script with a TTS provider (docs/API.md → "Podcast audio generation"). */
export type AudioJobStatus = 'QUEUED' | 'GENERATING' | 'READY' | 'FAILED' | 'CANCELLED';

export interface AudioJob {
  id: number;
  status: AudioJobStatus;
  provider: string;
  model: string;
  characters: number;
  charactersSent: number;
  chunksTotal: number;
  chunksDone: number;
  durationSeconds: number | null;
  error: string | null;
  cancelRequested: boolean;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface AudioGeneration {
  available: boolean;
  provider: string | null;
  providerLabel: string | null;
  model: string | null;
  voices: { speaker: 'HOST A' | 'HOST B'; voiceId: string; name: string }[];
  estimate: { characters: number; chunks: number; turns: number; sections: number; perRequestLimit: number; maxCharacters: number };
  budget: { dailyLimit: number; usedToday: number; remaining: number; resetsAt: string };
  job: AudioJob | null;
  blockers: string[];
}
