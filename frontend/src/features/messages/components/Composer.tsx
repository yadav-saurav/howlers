import { ImagePlus, SendHorizontal, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { IconButton } from '@/components/ui/Button';
import { toast } from '@/components/ui/Toast';
import { publishRealtime } from '@/features/realtime/RealtimeProvider';
import { throttleGate } from '../helpers';

export const MAX_BODY = 4000;
export const MAX_IMAGES = 4;
const TYPING_INTERVAL_MS = 2_000;

/** Auto-growing composer: Enter sends, Shift+Enter adds a line. Sends TYPING at most once per 2 s. */
export function Composer({
  conversationId,
  disabledReason,
  onSend,
}: {
  conversationId: number;
  disabledReason: string | null;
  onSend: (body: string, files: File[]) => void;
}) {
  const [body, setBody] = useState('');
  const [files, setFiles] = useState<{ file: File; url: string }[]>([]);
  const ta = useRef<HTMLTextAreaElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const gate = useRef(throttleGate(TYPING_INTERVAL_MS));
  const filesRef = useRef(files);
  filesRef.current = files;

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

  if (disabledReason) {
    return <p className="border-t border-border px-4 py-3 text-center text-sm text-muted" role="note">{disabledReason}</p>;
  }

  const canSend = (body.trim().length > 0 || files.length > 0) && body.length <= MAX_BODY;
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

  return (
    <div className="border-t border-border p-2">
      {files.length > 0 && (
        <ul className="mb-2 flex gap-2 px-1" aria-label="Attached images">
          {files.map((f, i) => (
            <li key={f.url} className="relative">
              <img src={f.url} alt={f.file.name} className="h-16 w-16 rounded-md object-cover" />
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
      <div className="flex items-end gap-1">
        <input ref={fileInput} type="file" accept="image/*" multiple hidden onChange={(e) => { addFiles(e.target.files); e.target.value = ''; }} />
        <IconButton label="Attach images" size="md" onClick={() => fileInput.current?.click()} disabled={files.length >= MAX_IMAGES}>
          <ImagePlus size={19} />
        </IconButton>
        <label className="sr-only" htmlFor={`composer-${conversationId}`}>Message</label>
        <textarea
          id={`composer-${conversationId}`}
          ref={ta}
          rows={1}
          value={body}
          maxLength={MAX_BODY + 200}
          placeholder="Write a message"
          onChange={(e) => {
            setBody(e.target.value);
            if (e.target.value.trim() && gate.current()) publishRealtime('/app/typing', { conversationId });
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              submit();
            }
          }}
          onPaste={(e) => {
            if (e.clipboardData.files.length) {
              e.preventDefault();
              addFiles(e.clipboardData.files);
            }
          }}
          className="max-h-40 min-h-10 flex-1 resize-none rounded-2xl border border-border bg-surface-2 px-3.5 py-2 text-sm leading-6 outline-none placeholder:text-faint focus:border-accent"
        />
        <IconButton label="Send" size="md" onClick={submit} disabled={!canSend} className="text-brand hover:text-brand">
          <SendHorizontal size={19} />
        </IconButton>
      </div>
      {body.length > MAX_BODY - 200 && (
        <p className={`mt-1 px-12 text-right text-[11px] ${body.length > MAX_BODY ? 'text-danger' : 'text-faint'}`}>{body.length}/{MAX_BODY}</p>
      )}
    </div>
  );
}
