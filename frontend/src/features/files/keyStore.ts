/**
 * Optional, per-device memory of E2EE file keys so the owner can download and share again from this browser.
 * Stored in localStorage (never sent to the server). Anyone with access to this browser profile, or any script
 * running on this origin, could read it — the UI says so and the feature is opt-in per upload.
 */
const STORE = 'strata.e2ee.keys.v1';

function read(): Record<string, string> {
  try {
    const raw = window.localStorage.getItem(STORE);
    const v = raw ? (JSON.parse(raw) as unknown) : {};
    return v && typeof v === 'object' ? (v as Record<string, string>) : {};
  } catch {
    return {};
  }
}

function write(v: Record<string, string>) {
  try {
    window.localStorage.setItem(STORE, JSON.stringify(v));
  } catch {
    /* storage unavailable: keys simply aren't remembered */
  }
}

export const keyStore = {
  get: (fileId: number): string | null => read()[String(fileId)] ?? null,
  set: (fileId: number, key: string) => write({ ...read(), [String(fileId)]: key }),
  forget: (fileId: number) => {
    const v = read();
    delete v[String(fileId)];
    write(v);
  },
};
