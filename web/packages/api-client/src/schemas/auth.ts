import { z } from 'zod';
import { instantSchema, localeSchema } from './common';

/**
 * Egyptian mobile numbers, local or international form.
 *
 * Mirrors the server-side pattern exactly. Client validation here is a UX affordance - it gives
 * instant feedback instead of a round trip - and never a substitute for the server's check.
 */
export const egyptianPhoneSchema = z
  .string()
  .regex(/^(\+20|0)?1[0125][0-9]{8}$/, 'رقم الهاتف غير صحيح');

/**
 * Length only, matching the server.
 *
 * Composition rules ("one uppercase, one digit, one symbol") reliably produce `Password1!` while
 * blocking genuinely strong passphrases - NIST SP 800-63B recommends length plus a breach check
 * instead, which the server applies.
 */
export const passwordSchema = z
  .string()
  .min(8, 'كلمة المرور يجب أن تكون 8 أحرف على الأقل')
  .max(128);

export const registerRequestSchema = z.object({
  firstName: z.string().min(2).max(80),
  lastName: z.string().max(80).optional(),
  email: z.string().email().max(191),
  phone: egyptianPhoneSchema.optional().or(z.literal('')),
  password: passwordSchema,
  locale: localeSchema.optional(),
  marketingOptIn: z.boolean().optional(),
});

export type RegisterRequest = z.infer<typeof registerRequestSchema>;

export const loginRequestSchema = z.object({
  emailOrPhone: z.string().min(1).max(191),
  password: z.string().min(1).max(128),
  rememberMe: z.boolean().optional(),
});

export type LoginRequest = z.infer<typeof loginRequestSchema>;

/**
 * Credentials from a successful authentication.
 *
 * `refreshToken` is `null` for web clients: the browser receives it as an `HttpOnly` cookie the
 * page cannot read, so an XSS that steals the in-memory access token still cannot walk away with a
 * 30-day credential. Mobile clients get it in the body and keep it in the platform keychain.
 */
export const tokenPairSchema = z.object({
  accessToken: z.string(),
  refreshToken: z.string().nullable().optional(),
  tokenType: z.string(),
  expiresIn: z.number().int(),
});

export type TokenPair = z.infer<typeof tokenPairSchema>;

export const userSchema = z.object({
  id: z.string().uuid(),
  email: z.string(),
  phone: z.string().nullable().optional(),
  firstName: z.string(),
  lastName: z.string().nullable().optional(),
  fullName: z.string(),
  avatarUrl: z.string().nullable().optional(),
  emailVerified: z.boolean(),
  phoneVerified: z.boolean(),
  status: z.enum(['ACTIVE', 'SUSPENDED', 'DELETED']),
  locale: z.string(),
  defaultCurrency: z.string(),
  marketingOptIn: z.boolean(),
  roles: z.array(z.string()),
  permissions: z.array(z.string()),
  lastLoginAt: instantSchema.nullable().optional(),
  createdAt: instantSchema,
});

export type User = z.infer<typeof userSchema>;

export const authResponseSchema = z.object({
  user: userSchema,
  tokens: tokenPairSchema,
});

export type AuthResponse = z.infer<typeof authResponseSchema>;

export const forgotPasswordRequestSchema = z.object({
  email: z.string().email().max(191),
});

export const resetPasswordRequestSchema = z
  .object({
    token: z.string().min(1),
    newPassword: passwordSchema,
    confirmPassword: z.string(),
  })
  .refine((data) => data.newPassword === data.confirmPassword, {
    message: 'كلمتا المرور غير متطابقتين',
    path: ['confirmPassword'],
  });

export const changePasswordRequestSchema = z
  .object({
    currentPassword: z.string().min(1).max(128),
    newPassword: passwordSchema,
    confirmPassword: z.string(),
  })
  .refine((data) => data.newPassword === data.confirmPassword, {
    message: 'كلمتا المرور غير متطابقتين',
    path: ['confirmPassword'],
  })
  .refine((data) => data.newPassword !== data.currentPassword, {
    message: 'كلمة المرور الجديدة يجب أن تختلف عن الحالية',
    path: ['newPassword'],
  });

export const updateProfileRequestSchema = z.object({
  firstName: z.string().min(2).max(80).optional(),
  lastName: z.string().max(80).optional(),
  phone: egyptianPhoneSchema.optional().or(z.literal('')),
  locale: localeSchema.optional(),
  avatarUrl: z.string().max(1024).optional(),
  marketingOptIn: z.boolean().optional(),
});

export type UpdateProfileRequest = z.infer<typeof updateProfileRequestSchema>;

/** Shaped for Egypt: governorate and area carry the routing, postal codes are rarely quoted. */
export const addressRequestSchema = z.object({
  label: z.string().max(40).optional(),
  recipientName: z.string().min(1).max(120),
  phone: egyptianPhoneSchema,
  countryCode: z.string().length(2).optional(),
  governorate: z.string().min(1).max(60),
  city: z.string().min(1).max(80),
  area: z.string().max(80).optional(),
  street: z.string().min(1).max(160),
  building: z.string().max(40).optional(),
  apartment: z.string().max(40).optional(),
  floor: z.string().max(20).optional(),
  landmark: z.string().max(160).optional(),
  postalCode: z.string().max(12).optional(),
  latitude: z.number().min(-90).max(90).optional(),
  longitude: z.number().min(-180).max(180).optional(),
  defaultShipping: z.boolean().optional(),
  defaultBilling: z.boolean().optional(),
});

export type AddressRequest = z.infer<typeof addressRequestSchema>;

export const addressSchema = addressRequestSchema.extend({
  id: z.string().uuid(),
  formatted: z.string(),
  defaultShipping: z.boolean(),
  defaultBilling: z.boolean(),
});

export type Address = z.infer<typeof addressSchema>;
