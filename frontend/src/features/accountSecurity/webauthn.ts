// Browser side of passkeys (D-041), using the WebAuthn API directly. The server sends options as JSON with binary
// fields base64url-encoded; these helpers turn them into the ArrayBuffers the API wants, and the resulting
// credential back into JSON for the server. Pure functions, so they're unit-tested without a browser.
import { ApiError } from '@/services/apiError';

export function b64urlToBuffer(s: string): ArrayBuffer {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/');
  const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
  const bin = atob(padded);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

export function bufferToB64url(b: ArrayBuffer | ArrayBufferView): string {
  const bytes = b instanceof ArrayBuffer ? new Uint8Array(b) : new Uint8Array(b.buffer, b.byteOffset, b.byteLength);
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

type Json = Record<string, unknown>;
interface DescriptorJson {
  type: string;
  id: string;
  transports?: string[];
}

function descriptors(list: unknown): PublicKeyCredentialDescriptor[] | undefined {
  if (!Array.isArray(list)) return undefined;
  return (list as DescriptorJson[]).map((d) => ({
    type: 'public-key',
    id: b64urlToBuffer(d.id),
    ...(d.transports ? { transports: d.transports as AuthenticatorTransport[] } : {}),
  }));
}

/** Server JSON (navigator.credentials.create's publicKey) to the API's options object. */
export function toCreationOptions(json: Json): PublicKeyCredentialCreationOptions {
  const user = json.user as { id: string; name: string; displayName: string };
  return {
    ...(json as unknown as PublicKeyCredentialCreationOptions),
    challenge: b64urlToBuffer(json.challenge as string),
    user: { ...user, id: b64urlToBuffer(user.id) },
    excludeCredentials: descriptors(json.excludeCredentials) ?? [],
  };
}

/** Server JSON (navigator.credentials.get's publicKey) to the API's options object. */
export function toRequestOptions(json: Json): PublicKeyCredentialRequestOptions {
  const allow = descriptors(json.allowCredentials);
  const opts: PublicKeyCredentialRequestOptions = {
    ...(json as unknown as PublicKeyCredentialRequestOptions),
    challenge: b64urlToBuffer(json.challenge as string),
  };
  if (allow && allow.length > 0) opts.allowCredentials = allow;
  else delete (opts as { allowCredentials?: unknown }).allowCredentials; // usernameless: let the device offer its passkeys
  return opts;
}

/** What the server parses (the shape of PublicKeyCredential.toJSON()). */
export interface CredentialJson {
  type: string;
  id: string;
  rawId: string;
  authenticatorAttachment?: string | null;
  response: Record<string, unknown>;
  clientExtensionResults: Record<string, unknown>;
}

export function registrationToJSON(cred: PublicKeyCredential): CredentialJson {
  const r = cred.response as AuthenticatorAttestationResponse;
  const transports = typeof r.getTransports === 'function' ? r.getTransports() : [];
  return {
    type: cred.type,
    id: cred.id,
    rawId: bufferToB64url(cred.rawId),
    authenticatorAttachment: cred.authenticatorAttachment ?? null,
    response: {
      clientDataJSON: bufferToB64url(r.clientDataJSON),
      attestationObject: bufferToB64url(r.attestationObject),
      transports,
    },
    clientExtensionResults: extensionResults(cred),
  };
}

export function assertionToJSON(cred: PublicKeyCredential): CredentialJson {
  const r = cred.response as AuthenticatorAssertionResponse;
  return {
    type: cred.type,
    id: cred.id,
    rawId: bufferToB64url(cred.rawId),
    authenticatorAttachment: cred.authenticatorAttachment ?? null,
    response: {
      clientDataJSON: bufferToB64url(r.clientDataJSON),
      authenticatorData: bufferToB64url(r.authenticatorData),
      signature: bufferToB64url(r.signature),
      userHandle: r.userHandle ? bufferToB64url(r.userHandle) : null,
    },
    clientExtensionResults: extensionResults(cred),
  };
}

function extensionResults(cred: PublicKeyCredential): Record<string, unknown> {
  const raw = typeof cred.getClientExtensionResults === 'function' ? cred.getClientExtensionResults() : {};
  // credProps is the only extension asked for; keep plain JSON values only.
  return JSON.parse(JSON.stringify(raw ?? {})) as Record<string, unknown>;
}

export type PasskeySupport = 'supported' | 'insecure-context' | 'unsupported';

/** Passkeys need the WebAuthn API and a secure context (HTTPS, or http://localhost in development). */
export function passkeySupport(win: { isSecureContext?: boolean; PublicKeyCredential?: unknown; navigator?: { credentials?: unknown } } = window): PasskeySupport {
  if (!win.isSecureContext) return 'insecure-context';
  if (typeof win.PublicKeyCredential !== 'function' || !win.navigator?.credentials) return 'unsupported';
  return 'supported';
}

export function supportMessage(s: PasskeySupport): string | null {
  if (s === 'insecure-context') return 'Passkeys need a secure connection (HTTPS). Open Howlers over https:// to use them.';
  if (s === 'unsupported') return 'This browser doesn’t support passkeys. Try a recent version of Chrome, Edge, Safari or Firefox.';
  return null;
}

/** A readable message for what went wrong in a passkey ceremony (browser DOMExceptions or server errors). */
export function webauthnErrorMessage(e: unknown, mode: 'register' | 'login'): string {
  if (e instanceof ApiError) return e.message;
  const name = e instanceof Error || (typeof e === 'object' && e !== null && 'name' in e) ? (e as { name: string }).name : '';
  switch (name) {
    case 'NotAllowedError':
      return mode === 'login'
        ? 'The passkey prompt was closed or timed out, or no passkey for Howlers is on this device.'
        : 'The passkey prompt was closed or timed out. Nothing was saved.';
    case 'InvalidStateError':
      return 'This device already has a passkey for your Howlers account.';
    case 'AbortError':
      return 'The passkey prompt was cancelled.';
    case 'SecurityError':
      return 'Passkeys aren’t available on this address: it doesn’t match the site’s passkey settings (relying party).';
    case 'NotSupportedError':
      return 'This device can’t create the kind of passkey Howlers needs (one that works without a username and checks your PIN or biometrics).';
    case 'ConstraintError':
      return 'This device can’t verify you with a PIN or biometrics, which passkeys on Howlers require.';
    default:
      return e instanceof Error && e.message ? e.message : 'Something went wrong with the passkey. Try again.';
  }
}
