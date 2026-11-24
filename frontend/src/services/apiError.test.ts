import { describe, expect, it } from 'vitest';
import { ApiError, connectionTrouble } from './apiError';

const err = (status: number) => new ApiError({ status, code: 'HTTP_ERROR', message: 'x' });

describe('connectionTrouble', () => {
  it('treats a network failure as offline', () => {
    expect(connectionTrouble(ApiError.network())).toBe('offline');
  });
  it('treats gateway and unavailable responses as maintenance', () => {
    expect(connectionTrouble(err(502))).toBe('maintenance');
    expect(connectionTrouble(err(503))).toBe('maintenance');
    expect(connectionTrouble(err(504))).toBe('maintenance');
  });
  it('leaves application errors and non-API errors alone', () => {
    expect(connectionTrouble(err(500))).toBeNull();
    expect(connectionTrouble(err(404))).toBeNull();
    expect(connectionTrouble(new Error('boom'))).toBeNull();
    expect(connectionTrouble(undefined)).toBeNull();
  });
});
