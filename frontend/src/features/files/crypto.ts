/**
 * End-to-end encryption for files, entirely in the browser (WebCrypto). The server only ever receives the
 * container produced here and never sees the key, the plaintext, the original file name or its type.
 * Threat model: docs/SECURITY_E2EE.md (also shown at /security/e2ee).
 *
 * Container format, version 1 (all integers big-endian):
 *
 *   offset  size  field
 *   0       8     magic "STRATAE2"
 *   8       1     version = 1
 *   9       4     plaintext chunk size in bytes
 *   13      16    random file nonce
 *   29      4     length M of the metadata block
 *   33      M     metadata: IV (12) + AES-256-GCM(JSON {name, type, size}) including its 16-byte tag
 *   33+M    ...   chunks: IV (12) + AES-256-GCM(chunk) including its 16-byte tag
 *
 * Every chunk is sealed with its own random IV. Its additional authenticated data is the 29-byte header (offsets 0-28) plus the
 * chunk index and a "last chunk" flag, so chunks can't be reordered, dropped, truncated at a chunk boundary or
 * spliced in from another file without decryption failing. The metadata uses the header plus the label "meta".
 */

export const MAGIC = new Uint8Array([0x53, 0x54, 0x52, 0x41, 0x54, 0x41, 0x45, 0x32]); // "STRATAE2"
export const VERSION = 1;
export const HEADER_BYTES = 29; // magic + version + chunk size + nonce
export const DEFAULT_CHUNK = 1024 * 1024; // 1 MiB of plaintext per chunk
export const PBKDF2_ITERATIONS = 600_000;
export const IV_BYTES = 12;
export const TAG_BYTES = 16;
const KEY_WRAP_AAD = new TextEncoder().encode('strata-e2ee-key-v1');

export class DecryptionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'DecryptionError';
  }
}

export interface FileMeta {
  name: string;
  type: string;
  size: number;
}

export interface WrappedKey {
  wrappedKey: string;
  salt: string;
  iterations: number;
}

const subtle = () => {
  const s = globalThis.crypto?.subtle;
  if (!s) throw new Error('This browser has no WebCrypto support (it needs a secure https:// page).');
  return s;
};

function randomBytes(n: number): Uint8Array {
  const b = new Uint8Array(n);
  globalThis.crypto.getRandomValues(b);
  return b;
}

// ------------------------------------------------------------------ base64url

export function toBase64Url(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function fromBase64Url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]*$/.test(value)) throw new DecryptionError('The key is not valid.');
  const b64 = value.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((value.length + 3) % 4);
  const raw = atob(b64);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

// ------------------------------------------------------------------ keys

export async function generateFileKey(): Promise<CryptoKey> {
  return subtle().generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
}

export async function exportKey(key: CryptoKey): Promise<string> {
  return toBase64Url(new Uint8Array(await subtle().exportKey('raw', key)));
}

export async function importKey(b64: string): Promise<CryptoKey> {
  const raw = fromBase64Url(b64.trim());
  if (raw.length !== 32) throw new DecryptionError('The key is not valid (expected 256 bits).');
  return subtle().importKey('raw', raw, { name: 'AES-GCM' }, true, ['encrypt', 'decrypt']);
}

/** The key part of a share URL fragment: "#k=<base64url>". Fragments are never sent to servers by browsers. */
export function keyFromFragment(hash: string): string | null {
  const h = hash.startsWith('#') ? hash.slice(1) : hash;
  for (const part of h.split('&')) {
    const [k, v] = part.split('=');
    if (k === 'k' && v && /^[A-Za-z0-9_-]{43}$/.test(v)) return v;
  }
  return null;
}

async function passphraseKey(passphrase: string, salt: Uint8Array, iterations: number): Promise<CryptoKey> {
  const base = await subtle().importKey('raw', new TextEncoder().encode(passphrase), 'PBKDF2', false, ['deriveKey']);
  return subtle().deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

/** Wraps the file key with a key derived from a passphrase (PBKDF2-SHA256, random 16-byte salt). */
export async function wrapKey(fileKey: CryptoKey, passphrase: string, iterations = PBKDF2_ITERATIONS): Promise<WrappedKey> {
  if (iterations < PBKDF2_ITERATIONS) throw new Error(`Use at least ${PBKDF2_ITERATIONS} PBKDF2 iterations`);
  const salt = randomBytes(16);
  const kek = await passphraseKey(passphrase, salt, iterations);
  const iv = randomBytes(IV_BYTES);
  const raw = new Uint8Array(await subtle().exportKey('raw', fileKey));
  const sealed = new Uint8Array(await subtle().encrypt({ name: 'AES-GCM', iv, additionalData: KEY_WRAP_AAD }, kek, raw));
  return { wrappedKey: toBase64Url(concat([iv, sealed])), salt: toBase64Url(salt), iterations };
}

export async function unwrapKey(w: WrappedKey, passphrase: string): Promise<CryptoKey> {
  const kek = await passphraseKey(passphrase, fromBase64Url(w.salt), w.iterations);
  const blob = fromBase64Url(w.wrappedKey);
  let raw: ArrayBuffer;
  try {
    raw = await subtle().decrypt({ name: 'AES-GCM', iv: blob.slice(0, IV_BYTES), additionalData: KEY_WRAP_AAD }, kek, blob.slice(IV_BYTES));
  } catch {
    throw new DecryptionError('That passphrase is not right.');
  }
  return subtle().importKey('raw', raw, { name: 'AES-GCM' }, true, ['encrypt', 'decrypt']);
}

// ------------------------------------------------------------------ container

function concat(parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

function u32(n: number): Uint8Array {
  const b = new Uint8Array(4);
  new DataView(b.buffer).setUint32(0, n);
  return b;
}

export function chunkAad(header: Uint8Array, index: number, last: boolean): Uint8Array {
  return concat([header, u32(index), new Uint8Array([last ? 1 : 0])]);
}

export interface Progress {
  (fraction: number): void;
}

/** Encrypts a file into a container Blob. Reads one chunk at a time, so the plaintext is never fully in memory twice. */
export async function encryptFile(
  file: Blob,
  meta: { name: string; type: string },
  key: CryptoKey,
  opts: { chunkSize?: number; onProgress?: Progress } = {},
): Promise<Blob> {
  const chunkSize = opts.chunkSize ?? DEFAULT_CHUNK;
  if (chunkSize < 1 || chunkSize > 64 * 1024 * 1024) throw new Error('Invalid chunk size');
  const header = concat([MAGIC, new Uint8Array([VERSION]), u32(chunkSize), randomBytes(16)]);
  const metaIv = randomBytes(IV_BYTES);
  const metaJson = new TextEncoder().encode(JSON.stringify({ name: meta.name, type: meta.type, size: file.size } satisfies FileMeta));
  const metaCt = new Uint8Array(
    await subtle().encrypt({ name: 'AES-GCM', iv: metaIv, additionalData: concat([header, new TextEncoder().encode('meta')]) }, key, metaJson),
  );
  const metaBlock = concat([metaIv, metaCt]);
  const parts: BlobPart[] = [concat([header, u32(metaBlock.length)]), metaBlock];
  const count = Math.max(1, Math.ceil(file.size / chunkSize));
  for (let i = 0; i < count; i++) {
    const plain = new Uint8Array(await file.slice(i * chunkSize, Math.min(file.size, (i + 1) * chunkSize)).arrayBuffer());
    const iv = randomBytes(IV_BYTES);
    const ct = await subtle().encrypt({ name: 'AES-GCM', iv, additionalData: chunkAad(header, i, i === count - 1) }, key, plain);
    parts.push(iv, new Uint8Array(ct));
    opts.onProgress?.((i + 1) / count);
  }
  return new Blob(parts, { type: 'application/octet-stream' });
}

async function readBytes(blob: Blob, start: number, end: number): Promise<Uint8Array> {
  return new Uint8Array(await blob.slice(start, end).arrayBuffer());
}

/** Decrypts only the metadata block (name, type, size). Fails like decryptFile on a wrong key or tampering. */
export async function decryptMeta(container: Blob, key: CryptoKey): Promise<{ meta: FileMeta; header: Uint8Array; bodyStart: number; chunkSize: number }> {
  const head = await readBytes(container, 0, HEADER_BYTES + 4);
  if (head.length < HEADER_BYTES + 4 || !MAGIC.every((b, i) => head[i] === b)) {
    throw new DecryptionError('This is not a Howlers encrypted file.');
  }
  if (head[8] !== VERSION) throw new DecryptionError(`Unsupported encrypted file version ${head[8]}.`);
  const view = new DataView(head.buffer, head.byteOffset, head.byteLength);
  const chunkSize = view.getUint32(9);
  const metaLen = view.getUint32(HEADER_BYTES);
  if (chunkSize < 1 || metaLen < IV_BYTES + TAG_BYTES || metaLen > 64 * 1024) throw new DecryptionError('The encrypted file is damaged.');
  const header = head.slice(0, HEADER_BYTES);
  const block = await readBytes(container, HEADER_BYTES + 4, HEADER_BYTES + 4 + metaLen);
  let json: ArrayBuffer;
  try {
    json = await subtle().decrypt(
      { name: 'AES-GCM', iv: block.slice(0, IV_BYTES), additionalData: concat([header, new TextEncoder().encode('meta')]) },
      key,
      block.slice(IV_BYTES),
    );
  } catch {
    throw new DecryptionError('Wrong key, or the file was modified.');
  }
  const meta = JSON.parse(new TextDecoder().decode(json)) as FileMeta;
  return { meta, header, bodyStart: HEADER_BYTES + 4 + metaLen, chunkSize };
}

/** Decrypts a container into the original file. Any wrong key, modification, reordering or truncation throws. */
export async function decryptFile(container: Blob, key: CryptoKey, onProgress?: Progress): Promise<{ meta: FileMeta; file: Blob }> {
  const { meta, header, bodyStart, chunkSize } = await decryptMeta(container, key);
  const full = IV_BYTES + chunkSize + TAG_BYTES;
  const bodyLen = container.size - bodyStart;
  if (bodyLen < IV_BYTES + TAG_BYTES) throw new DecryptionError('The encrypted file is truncated.');
  const count = Math.ceil(bodyLen / full);
  const parts: BlobPart[] = [];
  let total = 0;
  for (let i = 0; i < count; i++) {
    const c = await readBytes(container, bodyStart + i * full, Math.min(container.size, bodyStart + (i + 1) * full));
    if (c.length < IV_BYTES + TAG_BYTES) throw new DecryptionError('The encrypted file is truncated.');
    let plain: ArrayBuffer;
    try {
      plain = await subtle().decrypt({ name: 'AES-GCM', iv: c.slice(0, IV_BYTES), additionalData: chunkAad(header, i, i === count - 1) }, key, c.slice(IV_BYTES));
    } catch {
      throw new DecryptionError('Wrong key, or the file was modified.');
    }
    total += plain.byteLength;
    parts.push(plain);
    onProgress?.((i + 1) / count);
  }
  if (total !== meta.size) throw new DecryptionError('The encrypted file is truncated.');
  return { meta, file: new Blob(parts, { type: safeType(meta.type) }) };
}

export function safeType(t: string): string {
  return /^[a-z0-9.+-]+\/[a-z0-9.+-]+$/i.test(t) ? t : 'application/octet-stream';
}

/** A file name from inside the ciphertext, made safe to hand to the browser's save dialog. */
export function safeFileName(name: string): string {
  const n = (name || '')
    .split(/[/\\]/)
    .pop()!
    // eslint-disable-next-line no-control-regex -- stripping control and bidi characters from decrypted names is the point
    .replace(new RegExp('[\\u0000-\\u001f\\u007f\\u202a-\\u202e\\u2066-\\u2069]', 'g'), '')
    .trim();
  return n.replace(/^\.+/, '').slice(0, 255) || 'decrypted-file';
}

/** Accepts a raw key or any link that carries one in its #fragment. */
export function parseKeyInput(text: string): string | null {
  const t = text.trim();
  if (/^[A-Za-z0-9_-]{43}$/.test(t)) return t;
  const hash = t.indexOf('#');
  return hash >= 0 ? keyFromFragment(t.slice(hash)) : null;
}
