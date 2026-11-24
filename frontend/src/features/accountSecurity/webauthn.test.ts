import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/apiError';
import {
  assertionToJSON,
  b64urlToBuffer,
  bufferToB64url,
  passkeySupport,
  registrationToJSON,
  supportMessage,
  toCreationOptions,
  toRequestOptions,
  webauthnErrorMessage,
} from './webauthn';

const bytes = (...b: number[]) => new Uint8Array(b).buffer;

describe('base64url', () => {
  it('round-trips arbitrary bytes without padding or + and /', () => {
    const all = new Uint8Array(256).map((_, i) => i);
    const enc = bufferToB64url(all);
    expect(enc).not.toMatch(/[+/=]/);
    expect(new Uint8Array(b64urlToBuffer(enc))).toEqual(all);
    expect(bufferToB64url(bytes(0xfb, 0xff))).toBe('-_8');
    expect(new Uint8Array(b64urlToBuffer('-_8'))).toEqual(new Uint8Array([0xfb, 0xff]));
    expect(bufferToB64url(new Uint8Array([1, 2, 3]).subarray(1))).toBe('AgM');
  });
});

describe('options from the server', () => {
  it('decodes the challenge, user id and excluded credentials for create()', () => {
    const o = toCreationOptions({
      rp: { id: 'localhost', name: 'Howlers' },
      user: { id: 'AQID', name: 'mira_k', displayName: 'Mira' },
      challenge: 'BAUG',
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
      excludeCredentials: [{ type: 'public-key', id: 'BwgJ', transports: ['internal'] }],
      authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
      timeout: 120000,
    });
    expect(new Uint8Array(o.challenge as ArrayBuffer)).toEqual(new Uint8Array([4, 5, 6]));
    expect(new Uint8Array(o.user.id as ArrayBuffer)).toEqual(new Uint8Array([1, 2, 3]));
    expect(o.user.name).toBe('mira_k');
    expect(o.excludeCredentials).toHaveLength(1);
    expect(new Uint8Array(o.excludeCredentials![0].id as ArrayBuffer)).toEqual(new Uint8Array([7, 8, 9]));
    expect(o.excludeCredentials![0].transports).toEqual(['internal']);
    expect(o.authenticatorSelection?.residentKey).toBe('required');
  });

  it('leaves allowCredentials out for usernameless get()', () => {
    const o = toRequestOptions({ challenge: 'BAUG', rpId: 'localhost', allowCredentials: [], userVerification: 'required' });
    expect('allowCredentials' in o).toBe(false);
    expect(o.rpId).toBe('localhost');
    const scoped = toRequestOptions({ challenge: 'BAUG', allowCredentials: [{ type: 'public-key', id: 'AQ' }] });
    expect(scoped.allowCredentials).toHaveLength(1);
  });
});

describe('credentials to JSON', () => {
  const base = { type: 'public-key', id: 'AQID', rawId: bytes(1, 2, 3), authenticatorAttachment: 'platform', getClientExtensionResults: () => ({ credProps: { rk: true } }) };

  it('serialises an attestation', () => {
    const cred = {
      ...base,
      response: { clientDataJSON: bytes(10), attestationObject: bytes(11), getTransports: () => ['internal', 'hybrid'] },
    } as unknown as PublicKeyCredential;
    expect(registrationToJSON(cred)).toEqual({
      type: 'public-key',
      id: 'AQID',
      rawId: 'AQID',
      authenticatorAttachment: 'platform',
      response: { clientDataJSON: 'Cg', attestationObject: 'Cw', transports: ['internal', 'hybrid'] },
      clientExtensionResults: { credProps: { rk: true } },
    });
  });

  it('serialises an assertion, with or without a user handle', () => {
    const cred = {
      ...base,
      response: { clientDataJSON: bytes(1), authenticatorData: bytes(2), signature: bytes(3), userHandle: bytes(4) },
    } as unknown as PublicKeyCredential;
    expect(assertionToJSON(cred).response).toEqual({ clientDataJSON: 'AQ', authenticatorData: 'Ag', signature: 'Aw', userHandle: 'BA' });
    const noHandle = { ...base, response: { clientDataJSON: bytes(1), authenticatorData: bytes(2), signature: bytes(3), userHandle: null } } as unknown as PublicKeyCredential;
    expect(assertionToJSON(noHandle).response.userHandle).toBeNull();
  });
});

describe('support detection and errors', () => {
  it('needs a secure context and the API', () => {
    const PKC = function PublicKeyCredential() {};
    expect(passkeySupport({ isSecureContext: true, PublicKeyCredential: PKC, navigator: { credentials: {} } })).toBe('supported');
    expect(passkeySupport({ isSecureContext: false, PublicKeyCredential: PKC, navigator: { credentials: {} } })).toBe('insecure-context');
    expect(passkeySupport({ isSecureContext: true, navigator: {} })).toBe('unsupported');
    expect(supportMessage('supported')).toBeNull();
    expect(supportMessage('insecure-context')).toMatch(/HTTPS/);
  });

  it('turns browser and server failures into readable messages', () => {
    expect(webauthnErrorMessage(new DOMException('x', 'NotAllowedError'), 'login')).toMatch(/closed or timed out/);
    expect(webauthnErrorMessage(new DOMException('x', 'NotAllowedError'), 'register')).toMatch(/Nothing was saved/);
    expect(webauthnErrorMessage(new DOMException('x', 'InvalidStateError'), 'register')).toMatch(/already has a passkey/);
    expect(webauthnErrorMessage(new DOMException('x', 'SecurityError'), 'login')).toMatch(/relying party/);
    const api = new ApiError({ status: 401, code: 'PASSKEY_FLAGGED', message: 'This passkey is blocked' });
    expect(webauthnErrorMessage(api, 'login')).toBe('This passkey is blocked');
    expect(webauthnErrorMessage('weird', 'login')).toMatch(/Something went wrong/);
  });
});
