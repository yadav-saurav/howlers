import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { qk } from '@/services/queryKeys';
import { useAuthStore } from '@/store/authStore';
import type { AuthResponse } from '@/types/api';
import { accountSecurityApi } from './api';
import { assertionToJSON, registrationToJSON, toCreationOptions, toRequestOptions } from './webauthn';

export const securityKeys = {
  summary: ['account-security'] as const,
  passkeys: ['account-security', 'passkeys'] as const,
};

export function useSecuritySummary() {
  return useQuery({ queryKey: securityKeys.summary, queryFn: accountSecurityApi.summary });
}

export function usePasskeys() {
  return useQuery({ queryKey: securityKeys.passkeys, queryFn: accountSecurityApi.passkeys });
}

function useInvalidateSecurity() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: securityKeys.summary });
}

export function useSetAlerts() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: accountSecurityApi.setAlerts,
    onSuccess: (s) => qc.setQueryData(securityKeys.summary, s),
  });
}

/** Create a passkey on this device and save it to the account. */
export async function createPasskey(name?: string) {
  const start = await accountSecurityApi.passkeyOptions();
  const cred = (await navigator.credentials.create({ publicKey: toCreationOptions(start.publicKey) })) as PublicKeyCredential | null;
  if (!cred) throw new DOMException('No passkey was created', 'NotAllowedError');
  return accountSecurityApi.registerPasskey(start.requestId, registrationToJSON(cred), name);
}

/** Usernameless passkey sign-in; resolves to the same session a password login gives. */
export async function signInWithPasskey(): Promise<AuthResponse> {
  const start = await accountSecurityApi.passkeyLoginOptions();
  const cred = (await navigator.credentials.get({ publicKey: toRequestOptions(start.publicKey) })) as PublicKeyCredential | null;
  if (!cred) throw new DOMException('No passkey was chosen', 'NotAllowedError');
  return accountSecurityApi.passkeyLogin(start.requestId, assertionToJSON(cred));
}

export function useAddPasskey() {
  const qc = useQueryClient();
  const inv = useInvalidateSecurity();
  return useMutation({
    mutationFn: (name?: string) => createPasskey(name),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: securityKeys.passkeys });
      inv();
    },
  });
}

export function useRenamePasskey() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, name }: { id: number; name: string }) => accountSecurityApi.renamePasskey(id, name),
    onSuccess: () => qc.invalidateQueries({ queryKey: securityKeys.passkeys }),
  });
}

export function useDeletePasskey() {
  const qc = useQueryClient();
  const inv = useInvalidateSecurity();
  return useMutation({
    mutationFn: accountSecurityApi.deletePasskey,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: securityKeys.passkeys });
      inv();
    },
  });
}

export function useRevokeOtherSessions() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: accountSecurityApi.revokeOtherSessions,
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.sessions }),
  });
}

/** Stores a session obtained outside the password form (passkey or second step), like useLogin does. */
export function useAcceptSession() {
  const qc = useQueryClient();
  return (res: AuthResponse) => {
    useAuthStore.getState().setSession(res.accessToken, res.user);
    qc.invalidateQueries();
  };
}
