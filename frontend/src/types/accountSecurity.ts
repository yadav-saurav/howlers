// Account security (D-041, D-042): passkeys, two-factor authentication, new-sign-in emails. Mirrors docs/API.md.
import type { ID } from './api';

/** How a session was signed in. Null for sessions created before these features. */
export type AuthMethod = 'PASSWORD' | 'PASSWORD_TOTP' | 'PASSWORD_RECOVERY' | 'PASSKEY';

/** Returned by POST /auth/login instead of a session when the account has two-factor authentication on. */
export interface MfaChallenge {
  mfaRequired: true;
  mfaToken: string;
  expiresIn: number;
  methods: ('totp' | 'recovery')[];
}

export interface SecuritySummary {
  totp: { enabled: boolean; enabledAt: string | null; recoveryCodesRemaining: number };
  passkeys: number;
  newSignInAlerts: boolean;
  passkeyRpId: string;
}

export interface TotpSetup {
  secret: string;
  otpauthUri: string;
  /** Server-rendered SVG (rects and one path only). Shown through an <img> data URL, never injected as HTML. */
  qrSvg: string;
}

export interface RecoveryCodes {
  recoveryCodes: string[];
}

export interface PasskeyInfo {
  id: ID;
  name: string;
  createdAt: string;
  lastUsedAt: string | null;
  /** Backed up / synced by the platform (e.g. a password manager or phone keychain). */
  synced: boolean;
  /** Refused after a signature-counter regression (possible clone). */
  flagged: boolean;
  transports: string[];
}

/** Options for navigator.credentials.create/get as JSON (binary fields base64url), plus the server's request id. */
export interface CeremonyStart<T = Record<string, unknown>> {
  requestId: string;
  publicKey: T;
}
