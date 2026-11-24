import { http } from '@/services/apiClient';
import type { AuthResponse } from '@/types/api';
import type { CeremonyStart, PasskeyInfo, RecoveryCodes, SecuritySummary, TotpSetup } from '@/types/accountSecurity';
import type { CredentialJson } from './webauthn';

export const accountSecurityApi = {
  summary: () => http.get<SecuritySummary>('/auth/security'),
  setAlerts: (newSignInAlerts: boolean) => http.patch<SecuritySummary>('/auth/security', { newSignInAlerts }),

  totpSetup: (password: string) => http.post<TotpSetup>('/auth/2fa/totp/setup', { password }),
  totpConfirm: (code: string) => http.post<RecoveryCodes>('/auth/2fa/totp/confirm', { code }),
  totpDisable: (password: string, code: string) => http.post<void>('/auth/2fa/totp/disable', { password, code }),
  regenerateRecoveryCodes: (code: string) => http.post<RecoveryCodes>('/auth/2fa/recovery-codes', { code }),

  passkeys: () => http.get<PasskeyInfo[]>('/auth/passkeys'),
  passkeyOptions: () => http.post<CeremonyStart>('/auth/passkeys/options'),
  registerPasskey: (requestId: string, credential: CredentialJson, name?: string) =>
    http.post<PasskeyInfo>('/auth/passkeys', { requestId, credential, name }),
  renamePasskey: (id: number, name: string) => http.patch<void>(`/auth/passkeys/${id}`, { name }),
  deletePasskey: (id: number) => http.del<void>(`/auth/passkeys/${id}`),

  // Sign-in (no session yet, so no refresh-and-retry).
  passkeyLoginOptions: () => http.post<CeremonyStart>('/auth/passkey-login/options', undefined, { skipRefresh: true }),
  passkeyLogin: (requestId: string, credential: CredentialJson) =>
    http.post<AuthResponse>('/auth/passkey-login/verify', { requestId, credential }, { skipRefresh: true }),
  loginSecondStep: (mfaToken: string, code: string) =>
    http.post<AuthResponse>('/auth/login/2fa', { mfaToken, code }, { skipRefresh: true }),

  revokeOtherSessions: () => http.del<{ revoked: number }>('/auth/sessions/others'),
};
