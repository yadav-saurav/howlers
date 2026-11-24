import { base64UrlToBytes, browserSupport, bytesEqual, deviceLabel } from './pushLogic';

const env = {
  isSecureContext: true,
  hasServiceWorker: true,
  hasPushManager: true,
  hasNotification: true,
  workerEnabled: true,
  ua: 'Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0',
  standalone: false,
};

describe('push helpers', () => {
  it('decodes base64url VAPID keys with or without padding', () => {
    const key = 'BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8';
    const bytes = base64UrlToBytes(key);
    expect(bytes).toHaveLength(65);
    expect(bytes[0]).toBe(4);
    expect(bytesEqual(bytes.buffer as ArrayBuffer, base64UrlToBytes(`${key}=`))).toBe(true);
    expect(bytesEqual(null, bytes)).toBe(false);
    expect(bytesEqual(new Uint8Array(65).buffer, bytes)).toBe(false);
  });

  it('names devices from the user agent', () => {
    expect(deviceLabel(env.ua)).toBe('Firefox on Linux');
    expect(deviceLabel('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36 Edg/131.0')).toBe('Edge on Windows');
    expect(deviceLabel('Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36')).toBe('Chrome on Android');
    expect(deviceLabel('Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1')).toBe('Safari on iOS');
    expect(deviceLabel('curl/8')).toBe('Browser');
  });

  it('explains why push is unavailable in this browser', () => {
    expect(browserSupport(env)).toEqual({ ok: true });
    expect(browserSupport({ ...env, isSecureContext: false })).toEqual({ ok: false, reason: 'insecure' });
    expect(browserSupport({ ...env, hasPushManager: false })).toEqual({ ok: false, reason: 'unsupported' });
    expect(browserSupport({ ...env, workerEnabled: false })).toEqual({ ok: false, reason: 'no-worker' });
    const iphone = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) Safari/604.1';
    expect(browserSupport({ ...env, ua: iphone, hasPushManager: false })).toEqual({ ok: false, reason: 'ios-needs-install' });
    expect(browserSupport({ ...env, ua: iphone, standalone: true })).toEqual({ ok: true });
  });
});
