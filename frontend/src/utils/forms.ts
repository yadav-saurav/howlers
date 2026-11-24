import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { ApiError } from '@/services/apiError';

/**
 * Maps server-side `fieldErrors` onto react-hook-form fields.
 * Returns the message for a form-level error when nothing maps to a field.
 */
export function applyServerErrors<T extends FieldValues>(
  err: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
): string | null {
  if (!(err instanceof ApiError)) return err instanceof Error ? err.message : 'Something went wrong';
  let mapped = false;
  for (const [field, message] of Object.entries(err.fieldErrors)) {
    if ((fields as readonly string[]).includes(field)) {
      setError(field as Path<T>, { type: 'server', message });
      mapped = true;
    }
  }
  return mapped ? null : err.message;
}
