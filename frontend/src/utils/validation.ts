import { z } from 'zod';

// Mirrors the server rules in docs/API.md; the server remains the authority.
export const usernameSchema = z
  .string()
  .trim()
  .regex(/^[A-Za-z0-9_]{3,21}$/, '3–21 characters: letters, numbers and underscores');

export const passwordSchema = z
  .string()
  .min(8, 'At least 8 characters')
  .max(128, 'At most 128 characters')
  .regex(/[A-Za-z]/, 'Include at least one letter')
  .regex(/\d/, 'Include at least one number');

export const emailSchema = z.string().trim().email('Enter a valid email address');

export const loginSchema = z.object({
  login: z.string().trim().min(1, 'Enter your username or email'),
  password: z.string().min(1, 'Enter your password'),
});
export type LoginValues = z.infer<typeof loginSchema>;

export const registerSchema = z.object({
  username: usernameSchema,
  email: emailSchema,
  password: passwordSchema,
  displayName: z.string().trim().max(40, 'At most 40 characters').optional().or(z.literal('')),
});
export type RegisterValues = z.infer<typeof registerSchema>;

export const resetSchema = z
  .object({ newPassword: passwordSchema, confirm: z.string() })
  .refine((v) => v.newPassword === v.confirm, { path: ['confirm'], message: 'Passwords do not match' });
export type ResetValues = z.infer<typeof resetSchema>;

export const communitySlugSchema = z
  .string()
  .trim()
  .regex(/^[a-z0-9_]{3,24}$/, '3–24 lowercase letters, numbers or underscores');

export const tagSchema = z.string().regex(/^[a-z0-9-]{1,24}$/, 'Tags: lowercase letters, numbers, hyphens');
