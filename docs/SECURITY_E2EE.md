# End-to-end encrypted files: design and threat model

Howlers' Files area has an optional **Encrypt in browser** mode. This document says exactly what it protects and
what it does not. The same summary is shown in the app at `/security/e2ee`, and the upload and share screens link to it.

## How it works
- **Encryption happens in your browser** with WebCrypto: AES-256-GCM, a fresh random 256-bit key per file
  (`frontend/src/features/files/crypto.ts`).
- The file is split into 1 MiB chunks. Each chunk is sealed with its own random 96-bit IV and carries its own 128-bit
  authentication tag. The authenticated data of every chunk binds the container header (including a random per-file
  nonce), the chunk index and a "last chunk" flag. A modified, reordered, dropped or truncated chunk, or one spliced in
  from another file, makes decryption fail.
- The **original file name, MIME type and size** are encrypted in a metadata block inside the container. The server
  stores the upload as `application/octet-stream` with the label "Encrypted file" (or a label the owner types, which
  the UI marks as visible to the server).
- The server checks only that the upload starts with the container marker (`STRATAE2`, version 1) and stores it.
  It is marked `UNSCANNED`: the server can't inspect, preview, thumbnail or virus-check it.

### Getting the key to the recipient
1. **Key in the link.** The share URL ends in `#k=<key>`. Browsers never send the `#fragment` to any server, so our
   server logs and database never contain it. Anyone who has the whole link can decrypt.
2. **Passphrase.** The browser derives a key-encryption key from a passphrase with **PBKDF2-SHA256, 600,000
   iterations and a random 16-byte salt**, and wraps the file key with AES-256-GCM. The server stores only the wrapped
   key, salt and iteration count, and hands them out after any access-password check. The passphrase is never sent.
   The owner should send it through a different channel than the link.

Links for encrypted files can also have the normal server-side controls: expiry, an access password (bcrypt, attempts
rate-limited and logged), a download limit and revocation. Those limit who can fetch the ciphertext; they are not
part of the encryption.

### The owner's copy of the key
After an encrypted upload the app shows a **recovery key** once. Optionally (on by default, can be turned off per
upload), the key is remembered in this browser's `localStorage` so the owner can download and create new links from
the same device. It is never uploaded. If the owner loses every link, the recovery key and the browser's storage, the
file can't be decrypted by anyone, including Howlers.

## What it protects against
- **A curious or compromised server or database at rest.** Someone with read access to our storage, database, backups
  or server logs sees ciphertext, sizes and timestamps, never contents, names or types.
- **Our own staff** reading stored files.
- **Link leaks to the server side**: the fragment key never reaches our logs, proxies or analytics.
- **Tampering in storage or transit**: AES-GCM authentication detects any change; decryption fails instead of
  producing altered output.
- **Passphrase links found without the passphrase**: the wrapped key alone doesn't decrypt; guessing has to go through
  600k PBKDF2 iterations per attempt.

## What it does NOT protect against
- **A malicious or compromised server serving modified JavaScript.** The encryption code is delivered by the same
  server. If that server (or anyone who controls it) ships altered code, it could steal keys or plaintext the next
  time you encrypt or decrypt. This is the fundamental limit of web-based E2EE; native apps or audited, pinned
  builds (e.g. Subresource Integrity plus a published hash) would be needed to close it.
- **Anyone who has the full link** (for key-in-link shares), or the link and the passphrase. Forwarded links,
  chat logs, browser history and screenshots all count. Prefer passphrase links for sensitive files and short expiry.
- **Metadata.** The server sees the ciphertext size (close to the file size), upload and download times, the owner
  account, how many links exist and when they are used (the access log), and IP-derived visitor hashes.
- **Weak passphrases.** PBKDF2 slows guessing but can't save a short or common passphrase from an offline attack by
  someone who has the wrapped key (that includes the server). The app requires at least 10 characters.
- **Compromised devices**: malware or a malicious browser extension on the sender's or recipient's device, and anyone
  with access to a browser profile that remembers keys.
- **What recipients do with the decrypted file.** Once saved, it's an ordinary file.
- **Malware in the file.** The server can't scan encrypted content. Only open files from people you trust.

## Limits
- Decryption assembles the plaintext in memory before saving, so very large files (hundreds of MB) need a device with
  enough free memory. Encryption reads one 1 MiB chunk at a time.
- Requires a secure context (HTTPS or localhost) for WebCrypto.
- No key rotation: re-encrypting means uploading again.

## Large files (D-030)
In browsers with the File System Access API (Chromium-based desktop browsers), E2EE files of 64 MB or more are
decrypted as they download and written chunk by chunk to a file the user chooses. Only about two chunks (2 MiB) are in
memory, whatever the size. The format is unchanged and every chunk is still authenticated before it is written. A
failure (wrong key, tampering, truncation, network error) aborts the write, and the browser discards the partial file.
Other browsers decrypt in memory, as before, and warn above 500 MB.

## Tests
`frontend/src/features/files/crypto.test.ts` (Vitest, Node WebCrypto): round trip across chunks, empty files and
exact chunk multiples, no plaintext or name in the container, wrong key fails, flipped bits in header, metadata, body
or tag fail, dropped final chunk and swapped chunks fail, passphrase wrap/unwrap with 600k iterations, wrong passphrase
fails. The backend side (`FilesIT`) checks that only containers are accepted in E2EE mode and that they are stored and
served byte for byte.
