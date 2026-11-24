import type { ConversationSummary, DmMedia, DmMediaKind, DmMessage, ReplyPreview } from '@/types/messages';

/* Pure helpers for the inbox filters, replies, voice notes and read ticks. */

/* ------------------------------------------------------------------ inbox filters */

export type InboxFilter = 'all' | 'unread' | 'favourites' | 'rooms';

export const INBOX_FILTERS: readonly { id: InboxFilter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'unread', label: 'Unread' },
  { id: 'favourites', label: 'Favourites' },
  { id: 'rooms', label: 'Rooms' },
];

export function matchesFilter(c: ConversationSummary, f: InboxFilter): boolean {
  switch (f) {
    case 'unread':
      return c.unreadCount > 0;
    case 'favourites':
      return !!c.favorite;
    case 'rooms':
      return c.kind === 'ROOM';
    default:
      return true;
  }
}

export function filterConversations(list: readonly ConversationSummary[], f: InboxFilter): ConversationSummary[] {
  return list.filter((c) => matchesFilter(c, f));
}

/** The number of conversations with unread messages (the count on the Unread chip). */
export const unreadConversations = (list: readonly ConversationSummary[]) => list.filter((c) => c.unreadCount > 0).length;

export function emptyFilterText(f: InboxFilter): string {
  switch (f) {
    case 'unread':
      return 'You’re all caught up. No unread conversations.';
    case 'favourites':
      return 'No favourites yet. Star a conversation to keep it here.';
    case 'rooms':
      return 'You’re not in any pack rooms yet.';
    default:
      return 'No conversations yet.';
  }
}

/** Applies a favourite flag to a cached summary (optimistic update). */
export function withFavorite(s: ConversationSummary, conversationId: number, favorite: boolean): ConversationSummary {
  return s.id === conversationId && s.favorite !== favorite ? { ...s, favorite } : s;
}

/* ------------------------------------------------------------------ replies */

export const REPLY_EXCERPT_MAX = 120;

const MEDIA_LABEL: Record<DmMediaKind, string> = { IMAGE: 'Photo', AUDIO: 'Voice message' };

const clip = (s: string, max: number) => {
  const t = s.replace(/\s+/g, ' ').trim();
  return t.length > max ? `${t.slice(0, max - 1).trimEnd()}…` : t;
};

/** The quoted line for a reply: "Message deleted", the text, or "Photo" / "Voice message" for media without text. */
export function replyExcerpt(p: Pick<ReplyPreview, 'excerpt' | 'deleted' | 'mediaKind'>, max = REPLY_EXCERPT_MAX): string {
  if (p.deleted) return 'Message deleted';
  const text = clip(p.excerpt ?? '', max);
  if (text) return text;
  if (p.mediaKind) return MEDIA_LABEL[p.mediaKind];
  return 'Message';
}

/** An icon hint for the quote: shown beside text when the original carried media. */
export const replyMediaLabel = (p: Pick<ReplyPreview, 'deleted' | 'mediaKind'>): string | null =>
  !p.deleted && p.mediaKind ? MEDIA_LABEL[p.mediaKind] : null;

export const mediaKindOf = (m: Pick<DmMedia, 'kind'>): DmMediaKind => (m.kind === 'AUDIO' ? 'AUDIO' : 'IMAGE');

/** Builds the preview for the composer's quoted bar (and the pending bubble) from a loaded message. */
export function previewOf(m: DmMessage): ReplyPreview {
  const first = m.media[0];
  return {
    id: m.id,
    senderId: m.senderId,
    excerpt: m.deleted ? '' : clip(m.body, REPLY_EXCERPT_MAX),
    deleted: m.deleted,
    mediaKind: !m.deleted && first ? mediaKindOf(first) : null,
  };
}

/** "You" for my own messages, otherwise the name lookup (or a fallback). */
export function replySenderName(senderId: number | null, meId: number | undefined, nameOf: (id: number) => string | undefined): string {
  if (senderId == null) return 'Deleted account';
  if (senderId === meId) return 'You';
  return nameOf(senderId) ?? 'Someone';
}

/** Whether a message can be replied to (system lines and pending sends can't). */
export const canReply = (m: DmMessage) => m.kind !== 'SYSTEM' && !m.fromBlocked;

/* ------------------------------------------------------------------ swipe to reply */

export const SWIPE_TRIGGER_PX = 56;
export const SWIPE_MAX_PX = 80;

/**
 * How far a bubble follows a touch drag to the right. Returns null while the gesture is mostly vertical (so the list
 * keeps scrolling) and clamps with some resistance past the trigger point.
 */
export function swipeOffset(dx: number, dy: number): number | null {
  if (Math.abs(dy) > Math.abs(dx) || dx <= 0) return null;
  if (dx <= SWIPE_TRIGGER_PX) return dx;
  return Math.min(SWIPE_MAX_PX, SWIPE_TRIGGER_PX + (dx - SWIPE_TRIGGER_PX) * 0.35);
}

export const swipeTriggers = (offset: number) => offset >= SWIPE_TRIGGER_PX;

/* ------------------------------------------------------------------ voice notes */

export const MAX_VOICE_MS = 5 * 60_000;
export const MAX_VOICE_BYTES = 5 * 1024 * 1024;
/** Taps shorter than this are treated as accidental and discarded. */
export const MIN_VOICE_MS = 500;

/** "0:07", "1:23", "12:05"; negative or invalid values show as 0:00. */
export function formatDuration(ms: number | null | undefined): string {
  const total = Number.isFinite(ms) && (ms as number) > 0 ? Math.floor((ms as number) / 1000) : 0;
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

/** Prefers Opus in WebM (Chrome, Firefox, Edge), then MP4/AAC (Safari). "" lets the browser choose. */
export const RECORDER_MIME_CANDIDATES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'] as const;

export function pickRecorderMime(isSupported: (mime: string) => boolean): string {
  for (const m of RECORDER_MIME_CANDIDATES) {
    try {
      if (isSupported(m)) return m;
    } catch {
      // Some browsers throw for unknown types.
    }
  }
  return '';
}

/** The upload's content type without codec parameters, and a matching file name. */
export function voiceFileInfo(mime: string): { type: string; name: string } {
  const base = (mime.split(';')[0] || 'audio/webm').trim().toLowerCase();
  const ext = base === 'audio/mp4' ? 'm4a' : base === 'audio/ogg' ? 'ogg' : base === 'audio/mpeg' ? 'mp3' : 'webm';
  const type = ['audio/webm', 'audio/ogg', 'audio/mp4', 'audio/mpeg'].includes(base) ? base : 'audio/webm';
  return { type, name: `voice-note.${ext}` };
}

/** The server accepts 1..300000 ms. */
export const clampDuration = (ms: number) => Math.max(1, Math.min(MAX_VOICE_MS, Math.round(ms)));

/** A friendly message for getUserMedia / MediaRecorder failures. */
export function micErrorMessage(e: unknown): string {
  const name = (e as { name?: string } | null)?.name ?? '';
  if (name === 'NotAllowedError' || name === 'SecurityError' || name === 'PermissionDeniedError') {
    return 'Microphone access is blocked. Allow the microphone for this site in your browser settings to record voice messages.';
  }
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError' || name === 'OverconstrainedError') {
    return 'No microphone was found. Connect one and try again.';
  }
  if (name === 'NotReadableError' || name === 'TrackStartError') {
    return 'Your microphone is being used by another app. Close it and try again.';
  }
  if (name === 'NotSupportedError' || name === 'Unsupported') {
    return 'Voice messages aren’t supported in this browser.';
  }
  return 'Couldn’t start recording. Please try again.';
}

/**
 * Bar heights (0.2–1) for a voice note's waveform. We don't decode audio, so the shape is a stable pseudo-random
 * pattern seeded by the media id: each note looks different but never changes between renders.
 */
export function waveformBars(seed: number, count = 32): number[] {
  let x = (Math.abs(Math.floor(seed)) % 2147483646) + 1;
  const out: number[] = [];
  for (let i = 0; i < count; i++) {
    x = (x * 16807) % 2147483647;
    const r = (x - 1) / 2147483646;
    // A gentle envelope so the ends taper like speech.
    const env = 0.55 + 0.45 * Math.sin((Math.PI * (i + 0.5)) / count);
    out.push(Math.round((0.2 + 0.8 * r * env) * 100) / 100);
  }
  return out;
}

/** Seek target in ms for a click at `x` within a bar of `width`. */
export function seekPosition(x: number, width: number, durationMs: number): number {
  if (width <= 0 || !Number.isFinite(durationMs) || durationMs <= 0) return 0;
  return Math.max(0, Math.min(1, x / width)) * durationMs;
}

/* ------------------------------------------------------------------ read ticks */

export type TickState = 'sending' | 'sent' | 'read';

/** Direct conversations: two filled ticks once the other person's read marker reaches the message. */
export function tickState(messageId: number, otherLastRead: number | null): TickState {
  return otherLastRead != null && otherLastRead >= messageId ? 'read' : 'sent';
}
