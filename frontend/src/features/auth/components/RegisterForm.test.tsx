import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import { loginSchema, registerSchema } from '@/utils/validation';
import { LoginForm } from './LoginForm';
import { RegisterForm } from './RegisterForm';

function wrap(ui: ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('auth validation schemas', () => {
  it('rejects bad usernames and weak passwords', () => {
    const r = registerSchema.safeParse({ username: 'a!', email: 'nope', password: 'short' });
    expect(r.success).toBe(false);
    const fields = r.success ? [] : r.error.issues.map((i) => i.path[0]);
    expect(fields).toEqual(expect.arrayContaining(['username', 'email', 'password']));
  });
  it('requires a digit and a letter in passwords', () => {
    expect(registerSchema.safeParse({ username: 'ada_l', email: 'a@b.co', password: 'abcdefgh' }).success).toBe(false);
    expect(registerSchema.safeParse({ username: 'ada_l', email: 'a@b.co', password: '12345678' }).success).toBe(false);
    expect(registerSchema.safeParse({ username: 'ada_l', email: 'a@b.co', password: 'abcd1234' }).success).toBe(true);
  });
  it('login needs both fields', () => {
    expect(loginSchema.safeParse({ login: '', password: '' }).success).toBe(false);
  });
});

describe('RegisterForm', () => {
  afterEach(() => vi.restoreAllMocks());

  it('shows client-side errors and does not call the API for invalid input', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    wrap(<RegisterForm onSuccess={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(await screen.findByText(/3–21 characters: letters, numbers and underscores/)).toBeInTheDocument();
    expect(screen.getByText(/valid email/i)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalledWith(expect.stringContaining('/auth/register'), expect.anything());
  });

  it('maps server field errors onto the matching inputs', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('username-available')) return new Response(JSON.stringify({ available: true, reason: null }), { status: 200 });
        return new Response(
          JSON.stringify({ status: 409, code: 'CONFLICT', message: 'Email in use', fieldErrors: { email: 'An account with this email already exists' } }),
          { status: 409 },
        );
      }),
    );
    const onSuccess = vi.fn();
    wrap(<RegisterForm onSuccess={onSuccess} />);
    await userEvent.type(screen.getByLabelText(/^username/i), 'ada_l');
    await userEvent.type(screen.getByLabelText(/^email/i), 'ada@example.com');
    await userEvent.type(screen.getByLabelText(/^password/i), 'abcd1234');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(await screen.findByText('An account with this email already exists')).toBeInTheDocument();
    expect(screen.getByLabelText(/^email/i)).toHaveAttribute('aria-invalid', 'true');
    expect(onSuccess).not.toHaveBeenCalled();
  });
});

describe('LoginForm', () => {
  it('shows a form-level error from the server', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ status: 401, code: 'UNAUTHORIZED', message: 'Invalid username or password' }), { status: 401 })),
    );
    wrap(<LoginForm onSuccess={vi.fn()} />);
    await userEvent.type(screen.getByLabelText(/username or email/i), 'ada');
    await userEvent.type(screen.getByLabelText(/^password/i), 'wrong123');
    await userEvent.click(screen.getByRole('button', { name: /log in/i }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Invalid username or password'));
  });
});
