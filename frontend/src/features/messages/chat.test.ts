import { describe, expect, it } from 'vitest';
import type { ConversationSummary, DmMessage } from '@/types/messages';
import {
  MAX_VOICE_MS,
  SWIPE_MAX_PX,
  SWIPE_TRIGGER_PX,
  canReply,
  clampDuration,
  emptyFilterText,
  filterConversations,
  formatDuration,
  micErrorMessage,
  pickRecorderMime,
  previewOf,
  replyExcerpt,
  replyMediaLabel,
  replySenderName,
  seekPosition,
  swipeOffset,
  swipeTriggers,
  tickState,
  unreadConversations,
  voiceFileInfo,
  waveformBars,
  withFavorite,
} from './chat';

const conv = (id: number, extra: Partial<ConversationSummary> = {}): ConversationSummary => ({
  id,
  other: { id: 100 + id, username: `u${id}`, displayName: null, avatarUrl: null },
  lastMessage: null,
  unreadCount: 0,
  otherLastReadMessageId: null,
  online: false,
  canMessage: true,
  cannotMessageReason: null,
  updatedAt: '2026-11-20T10:00:00Z',
  kind: 'DIRECT',
  room: null,
  favorite: false,
  theme: null,
  ...extra,
});

const msg = (extra: Partial<DmMessage> = {}): DmMessage => ({
  id: 9,
  conversationId: 1,
  senderId: 2,
  body: 'hello there',
  media: [],
  createdAt: '2026-11-20T10:00:00Z',
  deleted: false,
  kind: 'USER',
  fromBlocked: false,
  replyTo: null,
  ...extra,
});

const audio = { id: 5, url: 'u', width: null, height: null, signed: true, expiresAt: null, kind: 'AUDIO' as const, contentType: 'audio/webm', durationMs: 3000 };
const image = { ...audio, id: 6, kind: 'IMAGE' as const, contentType: 'image/png', durationMs: null };

describe('inbox filters', () => {
  const list = [
    conv(1, { unreadCount: 2 }),
    conv(2, { favorite: true }),
    conv(3, { kind: 'ROOM', other: null, room: { name: 'Pack', iconEmoji: null, memberCount: 3, myRole: 'MEMBER' }, unreadCount: 1 }),
    conv(4),
  ];
  it('filters by unread, favourites and rooms', () => {
    expect(filterConversations(list, 'all').map((c) => c.id)).toEqual([1, 2, 3, 4]);
    expect(filterConversations(list, 'unread').map((c) => c.id)).toEqual([1, 3]);
    expect(filterConversations(list, 'favourites').map((c) => c.id)).toEqual([2]);
    expect(filterConversations(list, 'rooms').map((c) => c.id)).toEqual([3]);
  });
  it('counts unread conversations, not messages', () => {
    expect(unreadConversations(list)).toBe(2);
    expect(unreadConversations([])).toBe(0);
  });
  it('has an empty-state line per filter', () => {
    expect(emptyFilterText('favourites')).toMatch(/Star/);
    expect(emptyFilterText('unread')).toMatch(/caught up/);
  });
  it('applies a favourite flag only to the matching conversation', () => {
    const a = conv(1);
    expect(withFavorite(a, 1, true).favorite).toBe(true);
    expect(withFavorite(a, 2, true)).toBe(a);
    expect(withFavorite(a, 1, false)).toBe(a);
  });
});

describe('reply excerpts', () => {
  it('shows deleted, text, or the media kind', () => {
    expect(replyExcerpt({ excerpt: '', deleted: true, mediaKind: 'IMAGE' })).toBe('Message deleted');
    expect(replyExcerpt({ excerpt: '  hi\n there ', deleted: false, mediaKind: null })).toBe('hi there');
    expect(replyExcerpt({ excerpt: '', deleted: false, mediaKind: 'IMAGE' })).toBe('Photo');
    expect(replyExcerpt({ excerpt: '', deleted: false, mediaKind: 'AUDIO' })).toBe('Voice message');
    expect(replyExcerpt({ excerpt: '', deleted: false, mediaKind: null })).toBe('Message');
  });
  it('clips long text with an ellipsis', () => {
    const out = replyExcerpt({ excerpt: 'a'.repeat(200), deleted: false, mediaKind: null }, 20);
    expect(out).toHaveLength(20);
    expect(out.endsWith('…')).toBe(true);
  });
  it('labels media next to text, never for deleted originals', () => {
    expect(replyMediaLabel({ deleted: false, mediaKind: 'AUDIO' })).toBe('Voice message');
    expect(replyMediaLabel({ deleted: true, mediaKind: 'AUDIO' })).toBeNull();
    expect(replyMediaLabel({ deleted: false, mediaKind: null })).toBeNull();
  });
  it('builds a preview from a loaded message', () => {
    expect(previewOf(msg())).toEqual({ id: 9, senderId: 2, excerpt: 'hello there', deleted: false, mediaKind: null });
    expect(previewOf(msg({ body: '', media: [audio] })).mediaKind).toBe('AUDIO');
    expect(previewOf(msg({ body: '', media: [image] })).mediaKind).toBe('IMAGE');
    expect(previewOf(msg({ deleted: true, body: '' }))).toMatchObject({ deleted: true, excerpt: '', mediaKind: null });
  });
  it('names the sender', () => {
    const names = (id: number) => (id === 2 ? 'Bo' : undefined);
    expect(replySenderName(1, 1, names)).toBe('You');
    expect(replySenderName(2, 1, names)).toBe('Bo');
    expect(replySenderName(3, 1, names)).toBe('Someone');
    expect(replySenderName(null, 1, names)).toBe('Deleted account');
  });
  it('does not offer replies on system lines or blocked messages', () => {
    expect(canReply(msg())).toBe(true);
    expect(canReply(msg({ kind: 'SYSTEM' }))).toBe(false);
    expect(canReply(msg({ fromBlocked: true }))).toBe(false);
  });
});

describe('swipe to reply', () => {
  it('ignores vertical and leftward drags', () => {
    expect(swipeOffset(10, 30)).toBeNull();
    expect(swipeOffset(-20, 0)).toBeNull();
  });
  it('follows the finger, then resists past the trigger', () => {
    expect(swipeOffset(30, 5)).toBe(30);
    expect(swipeOffset(SWIPE_TRIGGER_PX + 20, 0)).toBeCloseTo(SWIPE_TRIGGER_PX + 7);
    expect(swipeOffset(500, 0)).toBe(SWIPE_MAX_PX);
    expect(swipeTriggers(SWIPE_TRIGGER_PX)).toBe(true);
    expect(swipeTriggers(SWIPE_TRIGGER_PX - 1)).toBe(false);
  });
});

describe('voice notes', () => {
  it('formats recording and playback times', () => {
    expect(formatDuration(0)).toBe('0:00');
    expect(formatDuration(7_400)).toBe('0:07');
    expect(formatDuration(83_000)).toBe('1:23');
    expect(formatDuration(MAX_VOICE_MS)).toBe('5:00');
    expect(formatDuration(725_000)).toBe('12:05');
    expect(formatDuration(-5)).toBe('0:00');
    expect(formatDuration(NaN)).toBe('0:00');
    expect(formatDuration(null)).toBe('0:00');
  });
  it('prefers Opus in WebM and falls back to MP4 for Safari', () => {
    expect(pickRecorderMime(() => true)).toBe('audio/webm;codecs=opus');
    expect(pickRecorderMime((m) => m === 'audio/mp4')).toBe('audio/mp4');
    expect(pickRecorderMime(() => false)).toBe('');
    expect(
      pickRecorderMime(() => {
        throw new Error('unsupported');
      }),
    ).toBe('');
  });
  it('maps the recorder type to an accepted upload type and file name', () => {
    expect(voiceFileInfo('audio/webm;codecs=opus')).toEqual({ type: 'audio/webm', name: 'voice-note.webm' });
    expect(voiceFileInfo('audio/mp4')).toEqual({ type: 'audio/mp4', name: 'voice-note.m4a' });
    expect(voiceFileInfo('audio/ogg;codecs=opus').type).toBe('audio/ogg');
    expect(voiceFileInfo('').type).toBe('audio/webm');
  });
  it('clamps the duration to what the server accepts', () => {
    expect(clampDuration(0)).toBe(1);
    expect(clampDuration(1_000_000)).toBe(MAX_VOICE_MS);
    expect(clampDuration(1234.6)).toBe(1235);
  });
  it('explains microphone errors', () => {
    expect(micErrorMessage({ name: 'NotAllowedError' })).toMatch(/blocked/);
    expect(micErrorMessage({ name: 'NotFoundError' })).toMatch(/No microphone/);
    expect(micErrorMessage(null)).toMatch(/try again/);
  });
  it('draws a stable waveform per note', () => {
    const a = waveformBars(42);
    expect(a).toHaveLength(32);
    expect(waveformBars(42)).toEqual(a);
    expect(waveformBars(43)).not.toEqual(a);
    expect(a.every((h) => h >= 0.2 && h <= 1)).toBe(true);
  });
  it('maps a click on the bar to a time', () => {
    expect(seekPosition(50, 200, 8000)).toBe(2000);
    expect(seekPosition(-10, 200, 8000)).toBe(0);
    expect(seekPosition(500, 200, 8000)).toBe(8000);
    expect(seekPosition(10, 0, 8000)).toBe(0);
  });
});

describe('read ticks', () => {
  it('turns read once the other read marker reaches the message', () => {
    expect(tickState(10, null)).toBe('sent');
    expect(tickState(10, 9)).toBe('sent');
    expect(tickState(10, 10)).toBe('read');
    expect(tickState(10, 12)).toBe('read');
  });
});
