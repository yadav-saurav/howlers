/**
 * Streaming decryption of the E2EE container (same format as crypto.ts, version 1, unchanged). Instead of holding the
 * whole ciphertext and the whole plaintext in memory, the network stream is re-framed into sealed chunks, each chunk
 * is decrypted on its own and handed to a sink, normally a file the user picked with the File System Access API
 * (`showSaveFilePicker`). Memory stays at about two chunks (2 MiB) whatever the file size.
 *
 * The last chunk is found by look-ahead (a frame is the last one when the stream ends right after it), so the total
 * size isn't needed up front, and the AAD's "last chunk" flag still detects truncation at a chunk boundary.
 * If anything fails mid-way, the caller aborts the writable stream, which discards the partial file.
 */
import { DecryptionError, HEADER_BYTES, IV_BYTES, TAG_BYTES, chunkAad, decryptMeta, type FileMeta } from './crypto';

/** Above this encrypted size we prefer streaming to disk when the browser supports it. */
export const STREAM_THRESHOLD_BYTES = 64 * 1024 * 1024;
/** Above this size, the in-memory fallback warns that the browser may run out of memory. */
export const MEMORY_WARNING_BYTES = 500 * 1024 * 1024;

/** Pulls exact byte counts out of an async stream of arbitrarily sized pieces. */
export class ByteReader {
  private pieces: Uint8Array[] = [];
  private buffered = 0;
  private done = false;
  private readonly it: AsyncIterator<Uint8Array>;

  constructor(source: AsyncIterable<Uint8Array>) {
    this.it = source[Symbol.asyncIterator]();
  }

  /** Up to n bytes; fewer only at the end of the stream. Each input byte is copied once. */
  async read(n: number): Promise<Uint8Array> {
    while (this.buffered < n && !this.done) {
      const r = await this.it.next();
      if (r.done) this.done = true;
      else if (r.value.length) {
        this.pieces.push(r.value);
        this.buffered += r.value.length;
      }
    }
    const out = new Uint8Array(Math.min(n, this.buffered));
    let o = 0;
    while (o < out.length) {
      const p = this.pieces[0];
      const take = Math.min(p.length, out.length - o);
      out.set(take === p.length ? p : p.subarray(0, take), o);
      o += take;
      if (take === p.length) this.pieces.shift();
      else this.pieces[0] = p.subarray(take);
    }
    this.buffered -= out.length;
    return out;
  }
}

export interface SealedFrame {
  index: number;
  /** IV (12) + ciphertext + tag (16). */
  bytes: Uint8Array;
  last: boolean;
}

/**
 * Splits the container body into sealed chunk frames of `frameSize` bytes (the last may be shorter) and marks the
 * last one by looking one frame ahead. Yields nothing for an empty body.
 */
export async function* sealedFrames(reader: ByteReader, frameSize: number): AsyncGenerator<SealedFrame> {
  let current = await reader.read(frameSize);
  let index = 0;
  while (current.length > 0) {
    if (current.length < frameSize) {
      yield { index, bytes: current, last: true };
      return;
    }
    const next = await reader.read(frameSize);
    yield { index, bytes: current, last: next.length === 0 };
    current = next;
    index++;
  }
}

export interface OpenedContainer {
  meta: FileMeta;
  /** Decrypted plaintext chunks in order. Throws DecryptionError on any tampering, reordering or truncation. */
  chunks: AsyncGenerator<Uint8Array>;
}

/** Reads and decrypts the header and metadata, then returns a lazy generator over the decrypted chunks. */
export async function openContainerStream(source: AsyncIterable<Uint8Array>, key: CryptoKey): Promise<OpenedContainer> {
  const reader = new ByteReader(source);
  const head = await reader.read(HEADER_BYTES + 4);
  if (head.length < HEADER_BYTES + 4) throw new DecryptionError('This isn’t a Howlers encrypted file.');
  const metaLen = new DataView(head.buffer, head.byteOffset, head.byteLength).getUint32(HEADER_BYTES);
  if (metaLen < IV_BYTES + TAG_BYTES || metaLen > 64 * 1024) throw new DecryptionError('The encrypted file is damaged.');
  const block = await reader.read(metaLen);
  const prefix = new Uint8Array(head.length + block.length);
  prefix.set(head);
  prefix.set(block, head.length);
  // Same checks as the in-memory path (magic, version, metadata authenticity).
  const { meta, header, chunkSize } = await decryptMeta(new Blob([prefix]), key);
  const frameSize = IV_BYTES + chunkSize + TAG_BYTES;

  async function* chunks(): AsyncGenerator<Uint8Array> {
    let total = 0;
    let frames = 0;
    for await (const f of sealedFrames(reader, frameSize)) {
      frames++;
      if (f.bytes.length < IV_BYTES + TAG_BYTES) throw new DecryptionError('The encrypted file is truncated.');
      let plain: ArrayBuffer;
      try {
        plain = await crypto.subtle.decrypt(
          { name: 'AES-GCM', iv: f.bytes.slice(0, IV_BYTES), additionalData: chunkAad(header, f.index, f.last) },
          key,
          f.bytes.slice(IV_BYTES),
        );
      } catch {
        throw new DecryptionError('Wrong key, or the file was modified.');
      }
      total += plain.byteLength;
      if (total > meta.size) throw new DecryptionError('The encrypted file is damaged.');
      yield new Uint8Array(plain);
    }
    if (frames === 0 || total !== meta.size) throw new DecryptionError('The encrypted file is truncated.');
  }

  return { meta, chunks: chunks() };
}

/** Bytes of a fetch Response body, reporting received/Content-Length progress. */
export async function* responseBytes(res: Response, onProgress?: (fraction: number) => void): AsyncGenerator<Uint8Array> {
  if (!res.body) {
    yield new Uint8Array(await res.arrayBuffer());
    onProgress?.(1);
    return;
  }
  const total = Number(res.headers.get('Content-Length') ?? 0);
  const reader = res.body.getReader();
  let loaded = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      loaded += value.length;
      if (total) onProgress?.(Math.min(1, loaded / total));
      yield value;
    }
  } finally {
    reader.releaseLock();
  }
}

/** Minimal typings for the File System Access API (not in TypeScript's DOM lib yet). */
interface WritableFile {
  write(data: Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort(reason?: unknown): Promise<void>;
}
interface SaveHandle {
  createWritable(): Promise<WritableFile>;
}
type SavePicker = (opts: { suggestedName?: string }) => Promise<SaveHandle>;

function picker(): SavePicker | null {
  const w = globalThis as unknown as { showSaveFilePicker?: SavePicker; isSecureContext?: boolean };
  return typeof w.showSaveFilePicker === 'function' && w.isSecureContext !== false ? w.showSaveFilePicker : null;
}

/** True when decrypted files can be streamed straight to a file on disk (Chromium-based browsers today). */
export function canStreamToDisk(): boolean {
  return picker() !== null;
}

/** True when the browser picker was dismissed: not an error worth showing. */
export function isPickerCancel(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError';
}

/**
 * Asks where to save (must run in a click handler: browsers require a user gesture), then writes each decrypted
 * chunk as it arrives. On any failure the partial file is discarded (`abort`).
 */
export async function saveChunksToDisk(
  chunks: AsyncIterable<Uint8Array>,
  fileName: string,
  totalBytes: number,
  onProgress?: (fraction: number) => void,
): Promise<void> {
  const show = picker();
  if (!show) throw new Error('This browser can’t save directly to disk.');
  const handle = await show({ suggestedName: fileName });
  const out = await handle.createWritable();
  let written = 0;
  try {
    for await (const c of chunks) {
      await out.write(c);
      written += c.length;
      if (totalBytes > 0) onProgress?.(Math.min(1, written / totalBytes));
    }
    await out.close();
  } catch (e) {
    await out.abort().catch(() => undefined);
    throw e;
  }
}

/** Collects decrypted chunks into a Blob (the in-memory fallback). */
export async function collectChunks(chunks: AsyncIterable<Uint8Array>, type: string): Promise<Blob> {
  const parts: BlobPart[] = [];
  for await (const c of chunks) parts.push(c);
  return new Blob(parts, { type });
}
