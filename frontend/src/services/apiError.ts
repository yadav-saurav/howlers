import type { ApiErrorBody } from '@/types/api';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: Record<string, string>;
  readonly traceId?: string;

  constructor(body: ApiErrorBody) {
    super(body.message || 'Request failed');
    this.name = 'ApiError';
    this.status = body.status;
    this.code = body.code;
    this.fieldErrors = body.fieldErrors ?? {};
    this.traceId = body.traceId;
  }

  static network(): ApiError {
    return new ApiError({
      status: 0,
      code: 'NETWORK_ERROR',
      message: 'Could not reach the server. Check your connection and try again.',
    });
  }
}

export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  if (err instanceof Error) return err.message;
  return 'Something went wrong';
}

/** Offline (no response at all) or maintenance (the proxy answered 502/503/504) rather than an application error. */
export function connectionTrouble(error: unknown): 'offline' | 'maintenance' | null {
  if (!(error instanceof ApiError)) return null;
  if (error.status === 0) return 'offline';
  if (error.status === 502 || error.status === 503 || error.status === 504) return 'maintenance';
  return null;
}
