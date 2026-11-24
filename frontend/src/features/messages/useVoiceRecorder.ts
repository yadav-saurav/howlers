import { useCallback, useEffect, useRef, useState } from 'react';
import { MAX_VOICE_BYTES, MAX_VOICE_MS, MIN_VOICE_MS, micErrorMessage, pickRecorderMime } from './chat';

export interface VoiceRecording {
  blob: Blob;
  durationMs: number;
  mime: string;
}

export type RecorderStatus = 'idle' | 'starting' | 'recording';

interface Session {
  rec: MediaRecorder;
  stream: MediaStream;
  chunks: Blob[];
  startedAt: number;
  mime: string;
  send: boolean;
}

/**
 * Records a voice note with MediaRecorder (Opus in WebM where supported, MP4/AAC on Safari). Recording stops by
 * itself at five minutes and is sent. The microphone is released as soon as recording ends or the component unmounts.
 */
export function useVoiceRecorder({ onComplete, onError }: { onComplete: (r: VoiceRecording) => void; onError: (message: string) => void }) {
  const [status, setStatus] = useState<RecorderStatus>('idle');
  const [elapsedMs, setElapsed] = useState(0);
  const session = useRef<Session | null>(null);
  const timer = useRef<number | null>(null);
  const cbs = useRef({ onComplete, onError });
  cbs.current = { onComplete, onError };

  const clearTimer = () => {
    if (timer.current != null) window.clearInterval(timer.current);
    timer.current = null;
  };

  const release = (s: Session) => s.stream.getTracks().forEach((t) => t.stop());

  const finish = useCallback((send: boolean) => {
    const s = session.current;
    clearTimer();
    if (!s) {
      setStatus('idle');
      return;
    }
    s.send = send;
    if (s.rec.state !== 'inactive') {
      try {
        s.rec.stop(); // onstop delivers the blob
        return;
      } catch {
        // Fall through and clean up.
      }
    }
    release(s);
    session.current = null;
    setStatus('idle');
    setElapsed(0);
  }, []);

  const start = useCallback(async () => {
    if (session.current || status !== 'idle') return;
    if (typeof window === 'undefined' || !navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      cbs.current.onError(micErrorMessage({ name: 'NotSupportedError' }));
      return;
    }
    setStatus('starting');
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
    } catch (e) {
      setStatus('idle');
      cbs.current.onError(micErrorMessage(e));
      return;
    }
    const mime = pickRecorderMime((m) => MediaRecorder.isTypeSupported(m));
    let rec: MediaRecorder;
    try {
      rec = new MediaRecorder(stream, mime ? { mimeType: mime, audioBitsPerSecond: 48_000 } : undefined);
    } catch (e) {
      stream.getTracks().forEach((t) => t.stop());
      setStatus('idle');
      cbs.current.onError(micErrorMessage(e));
      return;
    }
    const s: Session = { rec, stream, chunks: [], startedAt: performance.now(), mime: rec.mimeType || mime || 'audio/webm', send: false };
    session.current = s;
    rec.ondataavailable = (e) => {
      if (e.data.size > 0) s.chunks.push(e.data);
    };
    rec.onstop = () => {
      const durationMs = performance.now() - s.startedAt;
      release(s);
      if (session.current === s) session.current = null;
      setStatus('idle');
      setElapsed(0);
      if (!s.send) return;
      const blob = new Blob(s.chunks, { type: s.mime });
      if (durationMs < MIN_VOICE_MS || blob.size === 0) {
        cbs.current.onError('That was too short to send. Hold on a little longer.');
        return;
      }
      if (blob.size > MAX_VOICE_BYTES) {
        cbs.current.onError('That voice message is too large to send.');
        return;
      }
      cbs.current.onComplete({ blob, durationMs: Math.min(durationMs, MAX_VOICE_MS), mime: s.mime });
    };
    rec.start(250);
    setElapsed(0);
    setStatus('recording');
    timer.current = window.setInterval(() => {
      const ms = performance.now() - s.startedAt;
      setElapsed(Math.min(ms, MAX_VOICE_MS));
      if (ms >= MAX_VOICE_MS) finish(true); // hard stop at five minutes
    }, 200);
  }, [finish, status]);

  // Never keep the microphone after leaving the conversation.
  useEffect(
    () => () => {
      clearTimer();
      const s = session.current;
      if (s) {
        s.send = false;
        try {
          if (s.rec.state !== 'inactive') s.rec.stop();
        } catch {
          // ignore
        }
        release(s);
        session.current = null;
      }
    },
    [],
  );

  return {
    status,
    elapsedMs,
    start,
    /** Stops and sends. */
    stop: useCallback(() => finish(true), [finish]),
    /** Stops and throws the recording away. */
    cancel: useCallback(() => finish(false), [finish]),
  };
}
