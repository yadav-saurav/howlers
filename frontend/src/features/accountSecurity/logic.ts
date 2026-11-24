import type { AuthResponse } from '@/types/api';
import type { AuthMethod, MfaChallenge } from '@/types/accountSecurity';

export function isMfaChallenge(r: AuthResponse | MfaChallenge): r is MfaChallenge {
  return (r as MfaChallenge).mfaRequired === true && typeof (r as MfaChallenge).mfaToken === 'string';
}

export type CodeKind = 'totp' | 'recovery' | 'invalid';

/** 6 digits (spaces allowed) = authenticator code; 10 letters/digits (dash or spaces allowed) = recovery code. */
export function classifyCode(input: string): CodeKind {
  const c = input.replace(/[\s-]/g, '').toLowerCase();
  if (/^\d{6}$/.test(c)) return 'totp';
  if (/^[a-z0-9]{10}$/.test(c)) return 'recovery';
  return 'invalid';
}

export function normaliseCode(input: string): string {
  return input.replace(/[\s-]/g, '').toLowerCase();
}

export function authMethodLabel(m: AuthMethod | null | undefined): string | null {
  switch (m) {
    case 'PASSKEY':
      return 'Passkey';
    case 'PASSWORD_TOTP':
      return 'Password + 2FA';
    case 'PASSWORD_RECOVERY':
      return 'Recovery code';
    case 'PASSWORD':
      return 'Password';
    default:
      return null;
  }
}

/** Passkeys and authenticator codes are multi-factor; a recovery code is a second factor too, just a one-off. */
export function isMultiFactor(m: AuthMethod | null | undefined): boolean {
  return m === 'PASSKEY' || m === 'PASSWORD_TOTP' || m === 'PASSWORD_RECOVERY';
}

/** Plain-text file for "Download codes". */
export function recoveryCodesText(codes: string[], username: string, now = new Date()): string {
  return [
    `Howlers recovery codes for ${username}`,
    `Created ${now.toISOString().slice(0, 10)}. Each code works once.`,
    'Keep them somewhere safe and private, like a password manager.',
    '',
    ...codes,
    '',
  ].join('\n');
}

/** Groups the base32 secret in fours so it's easier to type into an app by hand. */
export function groupSecret(secret: string): string {
  return secret.replace(/(.{4})/g, '$1 ').trim();
}

/** data: URL for the server-rendered QR SVG, used as an <img src>, so it can never run as markup in the page. */
export function svgDataUrl(svg: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}
