import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import { LoginForm } from '@/features/auth/components/LoginForm';
import { useAuthStore } from '@/store/authStore';
import { PasskeyLoginButton } from './PasskeyLoginButton';

function wrap(ui: ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

const me = { id: 1, username: 'ada', onboardingCompleted: true };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

afterEach(() => {
  vi.unstubAllGlobals();
  useAuthStore.getState().clear();
});

async function passwordStep() {
  await userEvent.type(screen.getByLabelText(/username or email/i), 'ada');
  await userEvent.type(screen.getByLabelText(/^password/i), 'abcd1234');
  await userEvent.click(screen.getByRole('button', { name: /log in/i }));
}

describe('login with two-factor authentication', () => {
  it('asks for the code after the password, then signs in', async () => {
    const calls: { url: string; body: unknown }[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        calls.push({ url, body: init?.body ? JSON.parse(init.body as string) : undefined });
        if (url.endsWith('/auth/login')) return json({ mfaRequired: true, mfaToken: 'tok', expiresIn: 300, methods: ['totp', 'recovery'] });
        if (url.endsWith('/auth/login/2fa')) return json({ accessToken: 'acc', expiresIn: 900, user: me });
        return json({});
      }),
    );
    const onSuccess = vi.fn();
    wrap(<LoginForm onSuccess={onSuccess} />);
    await passwordStep();
    expect(await screen.findByText('Two-factor authentication')).toBeInTheDocument();
    // No session after the password alone.
    expect(useAuthStore.getState().accessToken).toBeNull();
    await userEvent.type(screen.getByLabelText(/authentication code/i), '123 456');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));
    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(me));
    expect(calls.find((c) => c.url.endsWith('/auth/login/2fa'))?.body).toEqual({ mfaToken: 'tok', code: '123456' });
    expect(useAuthStore.getState().accessToken).toBe('acc');
  });

  it('accepts a recovery code and shows wrong-code errors', async () => {
    let attempts = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url.endsWith('/auth/login')) return json({ mfaRequired: true, mfaToken: 'tok', expiresIn: 300, methods: ['totp', 'recovery'] });
        attempts++;
        return json({ status: 401, code: 'INVALID_CODE', message: 'That code isn’t right.' }, 401);
      }),
    );
    wrap(<LoginForm onSuccess={vi.fn()} />);
    await passwordStep();
    await userEvent.click(await screen.findByRole('button', { name: /use a recovery code/i }));
    const input = screen.getByLabelText(/recovery code/i);
    await userEvent.type(input, '12345');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));
    expect(await screen.findByText(/look like abcde-fghjk/)).toBeInTheDocument();
    expect(attempts).toBe(0);
    await userEvent.clear(input);
    await userEvent.type(input, 'abcde-fghjk');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));
    expect(await screen.findByText('That code isn’t right.')).toBeInTheDocument();
    expect(attempts).toBe(1);
  });

  it('goes back to the password step when the second-step token expired', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url.endsWith('/auth/login')) return json({ mfaRequired: true, mfaToken: 'tok', expiresIn: 300, methods: ['totp'] });
        return json({ status: 401, code: 'MFA_TOKEN_EXPIRED', message: 'That sign-in attempt expired. Enter your password again.' }, 401);
      }),
    );
    wrap(<LoginForm onSuccess={vi.fn()} />);
    await passwordStep();
    await userEvent.type(await screen.findByLabelText(/authentication code/i), '123456');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('That sign-in attempt expired');
    expect(screen.getByRole('button', { name: /log in/i })).toBeInTheDocument();
  });
});

describe('passkey sign-in button', () => {
  it('is disabled with an explanation when the page is not a secure context', () => {
    vi.stubGlobal('isSecureContext', false);
    wrap(<PasskeyLoginButton onSuccess={vi.fn()} />);
    expect(screen.getByRole('button', { name: /sign in with a passkey/i })).toBeDisabled();
    expect(screen.getByText(/secure connection/i)).toBeInTheDocument();
  });

  it('runs the ceremony and signs in', async () => {
    vi.stubGlobal('isSecureContext', true);
    vi.stubGlobal('PublicKeyCredential', function PublicKeyCredential() {});
    const get = vi.fn(async () => ({
      type: 'public-key',
      id: 'AQ',
      rawId: new Uint8Array([1]).buffer,
      authenticatorAttachment: 'platform',
      getClientExtensionResults: () => ({}),
      response: {
        clientDataJSON: new Uint8Array([2]).buffer,
        authenticatorData: new Uint8Array([3]).buffer,
        signature: new Uint8Array([4]).buffer,
        userHandle: new Uint8Array([5]).buffer,
      },
    }));
    Object.defineProperty(navigator, 'credentials', { value: { get, create: vi.fn() }, configurable: true });
    const sent: unknown[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        if (url.endsWith('/passkey-login/options')) return json({ requestId: 'r1', publicKey: { challenge: 'AQID', rpId: 'localhost', userVerification: 'required' } });
        sent.push(JSON.parse(init?.body as string));
        return json({ accessToken: 'acc', expiresIn: 900, user: me });
      }),
    );
    const onSuccess = vi.fn();
    wrap(<PasskeyLoginButton onSuccess={onSuccess} />);
    await userEvent.click(screen.getByRole('button', { name: /sign in with a passkey/i }));
    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(me));
    expect(get).toHaveBeenCalledTimes(1);
    expect(sent[0]).toMatchObject({ requestId: 'r1', credential: { id: 'AQ', rawId: 'AQ', response: { signature: 'BA', userHandle: 'BQ' } } });
    expect(useAuthStore.getState().accessToken).toBe('acc');
  });

  it('shows a readable error when the prompt is dismissed', async () => {
    vi.stubGlobal('isSecureContext', true);
    vi.stubGlobal('PublicKeyCredential', function PublicKeyCredential() {});
    Object.defineProperty(navigator, 'credentials', {
      value: { get: vi.fn(async () => { throw new DOMException('cancelled', 'NotAllowedError'); }), create: vi.fn() },
      configurable: true,
    });
    vi.stubGlobal('fetch', vi.fn(async () => json({ requestId: 'r1', publicKey: { challenge: 'AQID' } })));
    wrap(<PasskeyLoginButton onSuccess={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: /sign in with a passkey/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/closed or timed out/);
  });
});
