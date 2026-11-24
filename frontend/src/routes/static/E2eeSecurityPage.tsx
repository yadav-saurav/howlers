import { StaticPage } from './StaticPage';

/** In-app copy of docs/SECURITY_E2EE.md. Keep the two in sync. */
export default function E2eeSecurityPage() {
  return (
    <StaticPage title="End-to-end encrypted files" updated="November 2026">
      <p>
        When you turn on <strong>Encrypt in browser</strong> in Files, your browser encrypts the file before it is uploaded. This page
        says exactly what that protects and what it doesn’t.
      </p>
      <h2>How it works</h2>
      <ul>
        <li>Your browser encrypts with AES-256-GCM using a new random key for every file. Large files are encrypted in 1 MiB chunks, each with its own random IV and authentication tag, bound to its position so chunks can’t be reordered, dropped or swapped.</li>
        <li>The original file name and type are encrypted too. The server stores only ciphertext, labelled “Encrypted file” or with a label you choose (labels are visible to the server).</li>
        <li>The server can’t preview, scan or recover these files. They are marked “not scanned”.</li>
        <li>
          The recipient gets the key in one of two ways: in the link after <code>#k=</code> (browsers never send that part to servers), or
          wrapped with a passphrase using PBKDF2-SHA256 with 600,000 iterations and a random salt. The passphrase never leaves the browser.
        </li>
        <li>Expiry, access passwords, download limits and turning a link off still apply. They control who can fetch the ciphertext.</li>
      </ul>
      <h2>What it protects against</h2>
      <ul>
        <li>Someone reading our storage, database, backups or logs: they see ciphertext, sizes and times, not contents or names.</li>
        <li>Our own staff reading your files.</li>
        <li>Changes to the stored file: decryption fails instead of giving you altered content.</li>
        <li>Someone who finds a passphrase link but not the passphrase.</li>
      </ul>
      <h2>What it doesn’t protect against</h2>
      <ul>
        <li>
          <strong>A malicious or compromised server sending modified JavaScript.</strong> The code that encrypts is delivered by our server.
          Whoever controls the server could ship code that steals keys the next time you encrypt or decrypt. This is a limit of every
          web-based encryption tool.
        </li>
        <li><strong>Anyone with the full link</strong> (or the link and the passphrase) can decrypt. Treat links like keys.</li>
        <li><strong>Metadata:</strong> file size, upload and download times, your account, and the access log are visible to the server.</li>
        <li>Weak passphrases, compromised devices or browser extensions, and what people do with the file after decrypting it.</li>
        <li>Malware inside the file: we can’t scan encrypted content. Only open files from people you trust.</li>
      </ul>
      <h2>Keeping your key</h2>
      <p>
        After an encrypted upload you’re shown a recovery key. You can also let this browser remember the key (stored only on this
        device). If you lose every link, the recovery key and the browser’s storage, nobody can decrypt the file, including us.
      </p>
      <h2>Limits</h2>
      <p>
        Decrypting puts the whole file in memory before saving, so very large files need a device with enough free memory. Encryption needs a
        secure (https) page.
      </p>
    </StaticPage>
  );
}
