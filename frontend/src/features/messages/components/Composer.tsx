import { ImagePlus, Mic, SendHorizontal, Trash2, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { toast } from '@/components/ui/Toast';
import { publishRealtime } from '@/features/realtime/RealtimeProvider';
import type { ReplyPreview } from '@/types/messages';
import { cn } from '@/utils/format';
import { MAX_VOICE_MS, formatDuration } from '../chat';
import { throttleGate } from '../helpers';
import { useVoiceRecorder, type VoiceRecording } from '../useVoiceRecorder';
import { ReplyQuote } from './ReplyQuote';

export const MAX_BODY = 4000;
export const MAX_IMAGES = 4;
const TYPING_INTERVAL_MS = 2_000;

export interface ReplyDraft {
  preview: ReplyPreview;
  senderName: string;
}

const roundBtn =
  'inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-full shadow-md transition-[transform,opacity] duration-150 active:scale-95 disabled:opacity-50 disabled:pointer-events-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2';

/**
 * WhatsApp-style composer: a rounded bar with the photo button on the left and the auto-growing text box, then a round
 * button on the right that records a voice message, and turns into Send once there is text or a photo.
 * Enter sends, Shift+Enter adds a line, and TYPING is sent at most once every 2 s.
 */
export function Composer({
  conversationId,
  disabledReason,
  onSend,
  onSendVoice,
  reply,
  onCancelReply,
}: {
  conversationId: number;
  disabledReason: string | null;
  onSend: (body: string, files: File[]) => void;
  onSendVoice: (r: VoiceRecording) => void;
  reply: ReplyDraft | null;
  onCancelReply: () => void;
}) {
  const [body, setBody] = useState('');
  const [files, setFiles] = useState<{ file: File; url: string }[]>([]);
  const [micError, setMicError] = useState<string | null>(null);
  const ta = useRef<HTMLTextAreaElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const stopBtn = useRef<HTMLButtonElement>(null);
  const micBtn = useRef<HTMLButtonElement>(null);
  const gate = useRef(throttleGate(TYPING_INTERVAL_MS));
  const filesRef = useRef(files);
  filesRef.current = files;

  const recorder = useVoiceRecorder({
    onComplete: (r) => {
      setMicError(null);
      onSendVoice(r);
    },
    onError: setMicError,
  });
  const recording = recorder.status !== 'idle';

  useEffect(() => {
    const el = ta.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, [body]);

  // Reset when switching conversations.
  useEffect(() => {
    setBody('');
    setFiles((fs) => {
      fs.forEach((f) => URL.revokeObjectURL(f.url));
      return [];
    });
    gate.current = throttleGate(TYPING_INTERVAL_MS);
  }, [conversationId]);
  useEffect(() => () => filesRef.current.forEach((f) => URL.revokeObjectURL(f.url)), []);

  // Picking "Reply" puts the cursor in the box.
  const replyId = reply?.preview.id;
  useEffect(() => {
    if (replyId != null) ta.current?.focus();
  }, [replyId]);

  // Move focus with the recording controls so keyboard users can stop or cancel straight away.
  const wasRecording = useRef(false);
  useEffect(() => {
    if (recorder.status === 'recording') stopBtn.current?.focus();
    else if (recorder.status === 'idle' && wasRecording.current) (micBtn.current ?? ta.current)?.focus();
    wasRecording.current = recorder.status === 'recording';
  }, [recorder.status]);

  // Escape throws a recording away.
  const cancelRecording = recorder.cancel;
  useEffect(() => {
    if (!recording) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        cancelRecording();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [recording, cancelRecording]);

  if (disabledReason) {
    return (
      <div className="px-3 pb-3 pt-1">
        <p className="mx-auto max-w-md rounded-2xl bg-surface px-4 py-2.5 text-center text-sm text-muted shadow-sm" role="note">{disabledReason}</p>
      </div>
    );
  }

  const hasContent = body.trim().length > 0 || files.length > 0;
  const canSend = hasContent && body.length <= MAX_BODY;
  const submit = () => {
    if (!canSend) return;
    // The pending bubble makes its own previews, so these can go now.
    onSend(body.trim(), files.map((f) => f.file));
    files.forEach((f) => URL.revokeObjectURL(f.url));
    setFiles([]);
    setBody('');
  };

  const addFiles = (list: FileList | null) => {
    if (!list) return;
    const imgs = Array.from(list).filter((f) => f.type.startsWith('image/'));
    if (imgs.length < list.length) toast.error('Only images can be attached.');
    const room = MAX_IMAGES - files.length;
    if (imgs.length > room) toast.error(`Up to ${MAX_IMAGES} images per message.`);
    setFiles((fs) => [...fs, ...imgs.slice(0, Math.max(room, 0)).map((file) => ({ file, url: URL.createObjectURL(file) }))]);
  };

  const remaining = MAX_VOICE_MS - recorder.elapsedMs;

  return (
    <div className="px-2 pb-2 pt-1 sm:px-3 sm:pb-3">
      {micError && (
        <div role="alert" className="mx-1 mb-2 flex items-start gap-2 rounded-xl bg-surface px-3 py-2 text-sm text-danger shadow-sm">
          <Mic size={16} className="mt-0.5 shrink-0" aria-hidden="true" />
          <span className="flex-1">{micError}</span>
          <button type="button" onClick={() => setMicError(null)} aria-label="Dismiss" className="rounded-full p-0.5 text-muted hover:bg-surface-2 hover:text-text">
            <X size={14} />
          </button>
        </div>
      )}

      {(reply || files.length > 0) && !recording && (
        <div className="mx-1 mb-1.5 space-y-2 rounded-2xl bg-surface p-2 shadow-sm chat-pop">
          {reply && (
            <div className="flex items-start gap-2">
              <ReplyQuote preview={reply.preview} senderName={`Replying to ${reply.senderName}`} tone="composer" className="flex-1" />
              <button type="button" onClick={onCancelReply} aria-label="Cancel reply" className="rounded-full p-1.5 text-muted hover:bg-surface-2 hover:text-text">
                <X size={16} />
              </button>
            </div>
          )}
          {files.length > 0 && (
            <ul className="flex gap-2 px-1 pt-1" aria-label="Attached images">
              {files.map((f, i) => (
                <li key={f.url} className="relative">
                  <img src={f.url} alt={f.file.name} className="h-16 w-16 rounded-lg object-cover" />
                  <button
                    type="button"
                    aria-label={`Remove ${f.file.name}`}
                    onClick={() => {
                      URL.revokeObjectURL(f.url);
                      setFiles((fs) => fs.filter((_, j) => j !== i));
                    }}
                    className="absolute -right-1.5 -top-1.5 rounded-full bg-surface-3 p-0.5 text-text shadow"
                  >
                    <X size={12} />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div className="flex items-end gap-2">
        {recording ? (
          <div className="flex min-h-11 flex-1 items-center gap-2 rounded-full bg-surface px-2 shadow-sm chat-pop">
            <button type="button" onClick={recorder.cancel} aria-label="Delete recording" className="inline-flex h-9 w-9 items-center justify-center rounded-full text-danger hover:bg-surface-2">
              <Trash2 size={18} />
            </button>
            <span className="chat-rec-dot h-2.5 w-2.5 shrink-0 rounded-full bg-danger" aria-hidden="true" />
            <span className="font-mono text-sm tabular-nums text-text" role="timer" aria-label={`Recording, ${formatDuration(recorder.elapsedMs)}`}>
              {formatDuration(recorder.elapsedMs)}
            </span>
            <span className="truncate text-xs text-muted" aria-live="polite">
              {recorder.status === 'starting' ? 'Starting microphone…' : remaining <= 30_000 ? `${Math.ceil(remaining / 1000)} s left` : 'Recording… (max 5:00)'}
            </span>
          </div>
        ) : (
          <div className="flex min-h-11 flex-1 items-end rounded-[22px] bg-surface shadow-sm ring-1 ring-border/60 focus-within:ring-2 focus-within:ring-[color:var(--ct-accent)]">
            <input ref={fileInput} type="file" accept="image/*" multiple hidden onChange={(e) => { addFiles(e.target.files); e.target.value = ''; }} />
            <button
              type="button"
              aria-label="Attach photos"
              title="Attach photos"
              onClick={() => fileInput.current?.click()}
              disabled={files.length >= MAX_IMAGES}
              className="m-0.5 inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-muted transition-colors hover:bg-surface-2 hover:text-text disabled:pointer-events-none disabled:opacity-50"
            >
              <ImagePlus size={20} />
            </button>
            <label className="sr-only" htmlFor={`composer-${conversationId}`}>Message</label>
            <textarea
              id={`composer-${conversationId}`}
              ref={ta}
              rows={1}
              value={body}
              maxLength={MAX_BODY + 200}
              placeholder="Message"
              onChange={(e) => {
                setBody(e.target.value);
                if (e.target.value.trim() && gate.current()) publishRealtime('/app/typing', { conversationId });
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  submit();
                } else if (e.key === 'Escape' && reply) {
                  e.preventDefault();
                  onCancelReply();
                }
              }}
              onPaste={(e) => {
                if (e.clipboardData.files.length) {
                  e.preventDefault();
                  addFiles(e.clipboardData.files);
                }
              }}
              className="max-h-40 min-h-11 flex-1 resize-none bg-transparent py-2.5 pr-4 text-[15px] leading-6 text-text outline-none placeholder:text-faint"
            />
          </div>
        )}

        {recording ? (
          <button
            ref={stopBtn}
            type="button"
            onClick={recorder.stop}
            disabled={recorder.status !== 'recording'}
            aria-label="Send voice message"
            className={cn(roundBtn, 'chat-accent focus-visible:outline-[color:var(--ct-accent)]')}
          >
            <SendHorizontal size={20} />
          </button>
        ) : hasContent ? (
          <button
            type="button"
            onClick={submit}
            disabled={!canSend}
            aria-label="Send"
            title="Send (Enter)"
            className={cn(roundBtn, 'chat-accent chat-pop focus-visible:outline-[color:var(--ct-accent)]')}
          >
            <SendHorizontal size={20} />
          </button>
        ) : (
          <button
            ref={micBtn}
            type="button"
            onClick={() => {
              setMicError(null);
              void recorder.start();
            }}
            aria-label="Record voice message"
            title="Record voice message"
            className={cn(roundBtn, 'chat-accent focus-visible:outline-[color:var(--ct-accent)]')}
          >
            <Mic size={20} />
          </button>
        )}
      </div>
      {body.length > MAX_BODY - 200 && (
        <p className={`mt-1 px-14 text-right text-[11px] ${body.length > MAX_BODY ? 'text-danger' : 'text-faint'}`}>{body.length}/{MAX_BODY}</p>
      )}
    </div>
  );
}
