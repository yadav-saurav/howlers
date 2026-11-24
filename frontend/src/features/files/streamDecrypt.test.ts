// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { DecryptionError, encryptFile, generateFileKey } from './crypto';
import { ByteReader, collectChunks, openContainerStream, sealedFrames } from './streamDecrypt';

function sample(n: number): Uint8Array {
  const b = new Uint8Array(n);
  for (let i = 0; i < n; i++) b[i] = (i * 31 + 7) & 0xff;
  return b;
}

/** Feeds bytes in pieces of irregular sizes, like a network stream. */
async function* pieces(bytes: Uint8Array, sizes = [1, 7, 300, 4096, 13]): AsyncGenerator<Uint8Array> {
  let o = 0;
  let i = 0;
  while (o < bytes.length) {
    const n = sizes[i++ % sizes.length];
    yield bytes.slice(o, o + n);
    o += n;
  }
}

async function container(plain: Uint8Array, chunkSize: number) {
  const key = await generateFileKey();
  const blob = await encryptFile(new Blob([plain]), { name: 'big.bin', type: 'application/octet-stream' }, key, { chunkSize });
  return { key, bytes: new Uint8Array(await blob.arrayBuffer()) };
}

async function drain(chunks: AsyncIterable<Uint8Array>): Promise<Uint8Array> {
  return new Uint8Array(await (await collectChunks(chunks, 'application/octet-stream')).arrayBuffer());
}

describe('ByteReader', () => {
  it('returns exact sizes across piece boundaries and a short read at the end', async () => {
    const r = new ByteReader(pieces(sample(100), [3, 50, 1]));
    expect((await r.read(10)).length).toBe(10);
    expect(Array.from(await r.read(5))).toEqual(Array.from(sample(100).slice(10, 15)));
    expect((await r.read(200)).length).toBe(85);
    expect((await r.read(1)).length).toBe(0);
  });
});

describe('sealedFrames', () => {
  it('marks only the final frame as last, using look-ahead', async () => {
    const frames = [];
    for await (const f of sealedFrames(new ByteReader(pieces(sample(25))), 10)) frames.push(f);
    expect(frames.map((f) => [f.index, f.bytes.length, f.last])).toEqual([
      [0, 10, false],
      [1, 10, false],
      [2, 5, true],
    ]);
  });

  it('handles a body that is an exact multiple of the frame size, and an empty body', async () => {
    const frames = [];
    for await (const f of sealedFrames(new ByteReader(pieces(sample(20))), 10)) frames.push(f);
    expect(frames.map((f) => f.last)).toEqual([false, true]);
    const none = [];
    for await (const f of sealedFrames(new ByteReader(pieces(new Uint8Array(0))), 10)) none.push(f);
    expect(none).toEqual([]);
  });
});

describe('openContainerStream', () => {
  it('decrypts chunk by chunk to the same bytes as the original (format unchanged)', async () => {
    const plain = sample(5000);
    const { key, bytes } = await container(plain, 512);
    const opened = await openContainerStream(pieces(bytes), key);
    expect(opened.meta).toEqual({ name: 'big.bin', type: 'application/octet-stream', size: 5000 });
    let count = 0;
    const parts: Uint8Array[] = [];
    for await (const c of opened.chunks) {
      count++;
      parts.push(c);
    }
    expect(count).toBe(Math.ceil(5000 / 512));
    const joined = new Uint8Array(await new Blob(parts).arrayBuffer());
    expect(joined).toEqual(plain);
  });

  it('works when the plaintext is an exact number of chunks, and for an empty file', async () => {
    const exact = sample(1024);
    const a = await container(exact, 256);
    expect(await drain((await openContainerStream(pieces(a.bytes), a.key)).chunks)).toEqual(exact);
    const empty = await container(new Uint8Array(0), 256);
    expect((await drain((await openContainerStream(pieces(empty.bytes), empty.key)).chunks)).length).toBe(0);
  });

  it('detects truncation at a chunk boundary, tampering and a wrong key', async () => {
    const { key, bytes } = await container(sample(3000), 1000);
    const frame = 12 + 1000 + 16;
    const truncated = bytes.slice(0, bytes.length - frame); // drop the whole last frame: the new last fails its AAD
    await expect(drain((await openContainerStream(pieces(truncated), key)).chunks)).rejects.toBeInstanceOf(DecryptionError);

    const tampered = bytes.slice();
    tampered[tampered.length - 20] ^= 1;
    await expect(drain((await openContainerStream(pieces(tampered), key)).chunks)).rejects.toThrow(/modified/);

    await expect(openContainerStream(pieces(bytes), await generateFileKey())).rejects.toBeInstanceOf(DecryptionError);
    await expect(openContainerStream(pieces(new Uint8Array(10)), key)).rejects.toBeInstanceOf(DecryptionError);
  });
});
