import { describe, expect, it } from 'vitest';
import { authMethodLabel, classifyCode, groupSecret, isMfaChallenge, isMultiFactor, normaliseCode, recoveryCodesText, svgDataUrl } from './logic';
import type { AuthResponse } from '@/types/api';

describe('second-step helpers', () => {
  it('tells a challenge from a session', () => {
    expect(isMfaChallenge({ mfaRequired: true, mfaToken: 't', expiresIn: 300, methods: ['totp', 'recovery'] })).toBe(true);
    expect(isMfaChallenge({ accessToken: 'a', expiresIn: 900, user: {} } as unknown as AuthResponse)).toBe(false);
  });

  it('classifies and normalises codes', () => {
    expect(classifyCode('123 456')).toBe('totp');
    expect(classifyCode('123456')).toBe('totp');
    expect(classifyCode('12345')).toBe('invalid');
    expect(classifyCode('ABCDE-FGHJK')).toBe('recovery');
    expect(classifyCode('abcde fghjk')).toBe('recovery');
    expect(classifyCode('abc')).toBe('invalid');
    expect(normaliseCode(' ABCDE-fghjk ')).toBe('abcdefghjk');
  });

  it('labels how a session signed in', () => {
    expect(authMethodLabel('PASSKEY')).toBe('Passkey');
    expect(authMethodLabel('PASSWORD_TOTP')).toBe('Password + 2FA');
    expect(authMethodLabel('PASSWORD_RECOVERY')).toBe('Recovery code');
    expect(authMethodLabel(null)).toBeNull();
    expect(isMultiFactor('PASSKEY')).toBe(true);
    expect(isMultiFactor('PASSWORD')).toBe(false);
  });

  it('formats secrets, code files and the QR image source', () => {
    expect(groupSecret('JBSWY3DPEHPK3PXP')).toBe('JBSW Y3DP EHPK 3PXP');
    const text = recoveryCodesText(['aaaaa-bbbbb', 'ccccc-ddddd'], 'mira_k', new Date('2026-11-24T10:00:00Z'));
    expect(text).toContain('Howlers recovery codes for mira_k');
    expect(text).toContain('Created 2026-11-24');
    expect(text.split('\n')).toEqual(expect.arrayContaining(['aaaaa-bbbbb', 'ccccc-ddddd']));
    const url = svgDataUrl('<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0h1"/></svg>');
    expect(url.startsWith('data:image/svg+xml;charset=utf-8,')).toBe(true);
    expect(url).not.toContain('<');
  });
});
