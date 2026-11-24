// @vitest-environment node
// Node's WebCrypto (globalThis.crypto.subtle) is the same API browsers expose.
import { describe, expect, it } from 'vitest';
import {
  DecryptionError,
  HEADER_BYTES,
  MAGIC,
  PBKDF2_ITERATIONS,
  decryptFile,
  decryptMeta,
  encryptFile,
  exportKey,
  fromBase64Url,
  generateFileKey,
  importKey,
  keyFromFragment,
  parseKeyInput,
  safeFileName,
  toBase64Url,
  unwrapKey,
  wrapKey,
} from './crypto';

function sample(n: number): Uint8Array {
  const b = new Uint8Array(n);
  for (let i = 0; i < n; i++) b[i] = (i * 31 + 7) & 0xff;
  return b;
}

async function bytes(b: Blob): Promise<Uint8Array> {
  return new Uint8Array(await b.arrayBuffer());
}

const meta = { name: 'Board minutes – Q3.pdf', type: 'application/pdf' };

describe('E2EE file container', () => {
  it('round-trips across several chunks, including name and type', async () => {
    const key = await generateFileKey();
    const plain = sample(10_000);
    const progress: number[] = [];
    const container = await encryptFile(new Blob([plain]), meta, key, { chunkSize: 4096, onProgress: (f) => progress.push(f) });
    const head = await bytes(container.slice(0, 8));
    expect(Array.from(head)).toEqual(Array.from(MAGIC));
    // 3 chunks: each adds a 12-byte IV and a 16-byte tag.
    expect(progress).toEqual([1 / 3, 2 / 3, 1]);
    const out = await decryptFile(container, key);
    expect(out.meta).toEqual({ ...meta, size: 10_000 });
    expect(await bytes(out.file)).toEqual(plain);
    expect(out.file.type).toBe('application/pdf');
  });

  it('handles empty files and exact chunk multiples', async () => {
    const key = await generateFileKey();
    for (const n of [0, 4096, 8192]) {
      const container = await encryptFile(new Blob([sample(n)]), meta, key, { chunkSize: 4096 });
      const out = await decryptFile(container, key);
      expect(await bytes(out.file)).toEqual(sample(n));
    }
  });

  it('does not leak the file name or content into the container', async () => {
    const key = await generateFileKey();
    const plain = new TextEncoder().encode('TOP SECRET payroll numbers '.repeat(50));
    const container = await bytes(await encryptFile(new Blob([plain]), { name: 'payroll.csv', type: 'text/csv' }, key));
    const asText = new TextDecoder('latin1').decode(container);
    expect(asText).not.toContain('payroll');
    expect(asText).not.toContain('TOP SECRET');
    expect(asText).not.toContain('text/csv');
  });

  it('fails with the wrong key', async () => {
    const container = await encryptFile(new Blob([sample(5000)]), meta, await generateFileKey(), { chunkSize: 1024 });
    await expect(decryptFile(container, await generateFileKey())).rejects.toBeInstanceOf(DecryptionError);
  });

  it('fails when any ciphertext byte, the header or the metadata is modified', async () => {
    const key = await generateFileKey();
    const container = await bytes(await encryptFile(new Blob([sample(5000)]), meta, key, { chunkSize: 1024 }));
    for (const pos of [10 /* chunk size field */, 20 /* file nonce */, HEADER_BYTES + 4 + 15 /* metadata */, container.length - 1 /* last tag */, container.length - 700 /* body */]) {
      const tampered = container.slice();
      tampered[pos] ^= 0x01;
      await expect(decryptFile(new Blob([tampered]), key)).rejects.toBeInstanceOf(DecryptionError);
    }
  });

  it('detects truncation and reordered chunks', async () => {
    const key = await generateFileKey();
    const chunk = 1024;
    const c = await bytes(await encryptFile(new Blob([sample(4096)]), meta, key, { chunkSize: chunk }));
    const full = 12 + chunk + 16;
    const { bodyStart } = await decryptMeta(new Blob([c]), key);
    // Drop the final chunk: the new last chunk was not sealed as "last".
    await expect(decryptFile(new Blob([c.slice(0, c.length - full)]), key)).rejects.toBeInstanceOf(DecryptionError);
    // Swap chunks 0 and 1: each chunk's index is authenticated.
    const swapped = c.slice();
    swapped.set(c.slice(bodyStart + full, bodyStart + 2 * full), bodyStart);
    swapped.set(c.slice(bodyStart, bodyStart + full), bodyStart + full);
    await expect(decryptFile(new Blob([swapped]), key)).rejects.toBeInstanceOf(DecryptionError);
  });

  it('rejects data that is not a container', async () => {
    await expect(decryptFile(new Blob([new TextEncoder().encode('%PDF-1.7 hello world, not encrypted at all')]), await generateFileKey()))
      .rejects.toThrow('not a Howlers encrypted file');
  });
});

describe('keys', () => {
  it('exports and imports raw 256-bit keys as base64url for the URL fragment', async () => {
    const key = await generateFileKey();
    const b64 = await exportKey(key);
    expect(b64).toMatch(/^[A-Za-z0-9_-]{43}$/);
    const container = await encryptFile(new Blob([sample(100)]), meta, key);
    const again = await importKey(b64);
    expect(await bytes((await decryptFile(container, again)).file)).toEqual(sample(100));
    expect(keyFromFragment(`#k=${b64}`)).toBe(b64);
    expect(keyFromFragment('#x=1&k=' + b64)).toBe(b64);
    expect(keyFromFragment('#k=short')).toBeNull();
    expect(parseKeyInput(` ${b64} `)).toBe(b64);
    expect(parseKeyInput(`https://howlers.test/s/tok#k=${b64}`)).toBe(b64);
    expect(parseKeyInput('https://howlers.test/s/tok')).toBeNull();
    await expect(importKey('abc')).rejects.toBeInstanceOf(DecryptionError);
  });

  it('wraps a key with a passphrase (PBKDF2-SHA256, 600k iterations) and refuses a wrong passphrase', async () => {
    const key = await generateFileKey();
    const w = await wrapKey(key, 'correct horse battery staple');
    expect(w.iterations).toBe(PBKDF2_ITERATIONS);
    expect(fromBase64Url(w.salt)).toHaveLength(16);
    expect(fromBase64Url(w.wrappedKey)).toHaveLength(12 + 32 + 16);
    const unwrapped = await unwrapKey(w, 'correct horse battery staple');
    expect(await exportKey(unwrapped)).toBe(await exportKey(key));
    await expect(unwrapKey(w, 'Correct horse battery staple')).rejects.toThrow('passphrase');
    await expect(wrapKey(key, 'pw', 1000)).rejects.toThrow();
  }, 30_000);

  it('base64url round-trips arbitrary bytes', () => {
    const b = sample(257);
    expect(fromBase64Url(toBase64Url(b))).toEqual(b);
  });

  it('makes decrypted names safe to save', () => {
    expect(safeFileName('../../etc/passwd')).toBe('passwd');
    expect(safeFileName(`..\\evil${String.fromCharCode(0x202e)}.txt`)).toBe('evil.txt');
    expect(safeFileName('')).toBe('decrypted-file');
  });
});
