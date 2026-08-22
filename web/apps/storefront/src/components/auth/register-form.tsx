'use client';

import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { ApiError, registerRequestSchema, type RegisterRequest } from '@eshop/api-client';
import { Link, useRouter } from '@/i18n/routing';
import { Button } from '@/components/ui/button';
import { Card, Field, Input } from '@/components/ui/primitives';
import { browserApi } from '@/lib/api-browser';
import { useAuthStore } from '@/stores/auth-store';
import { clearSessionId, getOrCreateSessionId } from '@/lib/utils';

interface Labels {
  title: string;
  subtitle: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  password: string;
  marketingOptIn: string;
  submit: string;
  haveAccount: string;
  login: string;
  continueWithGoogle: string;
  orContinueWith: string;
  optional: string;
  emailTaken: string;
  rateLimited: string;
  somethingWentWrong: string;
}

export function RegisterForm({ labels }: { labels: Labels }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const setSession = useAuthStore((state) => state.setSession);

  const form = useForm<RegisterRequest>({
    resolver: zodResolver(registerRequestSchema),
    defaultValues: {
      firstName: '',
      lastName: '',
      email: '',
      phone: '',
      password: '',
      marketingOptIn: false,
    },
  });

  const register = useMutation({
    mutationFn: (values: RegisterRequest) =>
      browserApi().auth.register({
        ...values,
        // The API rejects an empty string against its phone pattern; omitting the field entirely is
        // what "no phone given" means.
        phone: values.phone?.trim() ? values.phone : undefined,
        lastName: values.lastName?.trim() ? values.lastName : undefined,
      }),

    onSuccess: async (response) => {
      // Registration returns a usable session immediately — browsing and checkout do not require a
      // verified address, and blocking on the verification email would abandon most sign-ups.
      setSession(response.user, response.tokens.accessToken);

      const sessionId = getOrCreateSessionId();
      if (sessionId) {
        try {
          await browserApi().cart.merge(sessionId);
          clearSessionId();
        } catch {
          /* the guest cart stays where it is */
        }
      }

      queryClient.clear();
      router.replace('/');
      router.refresh();
    },

    onError: (error) => {
      // Field-level errors from the API are attached to the matching input rather than shown as a
      // banner, so the fix is where the problem is.
      if (error instanceof ApiError) {
        for (const [field, message] of Object.entries(error.fieldErrors())) {
          form.setError(field as keyof RegisterRequest, { message });
        }
      }
    },
  });

  function errorMessage(): string | null {
    if (!(register.error instanceof ApiError)) {
      return register.error ? labels.somethingWentWrong : null;
    }
    if (register.error.is('EMAIL_ALREADY_REGISTERED')) return labels.emailTaken;
    if (register.error.is('RATE_LIMITED')) return labels.rateLimited;
    // A validation failure is already shown inline on the offending field.
    if (register.error.is('VALIDATION_ERROR')) return null;
    return register.error.detail || labels.somethingWentWrong;
  }

  const error = errorMessage();

  return (
    <Card className="p-6">
      <h1 className="text-xl font-bold">{labels.title}</h1>
      <p className="mt-1 text-sm text-[var(--muted-foreground)]">{labels.subtitle}</p>

      <form
        onSubmit={form.handleSubmit((values) => register.mutate(values))}
        className="mt-6 space-y-4"
        noValidate
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            id="firstName"
            label={labels.firstName}
            error={form.formState.errors.firstName?.message}
            required
          >
            <Input
              id="firstName"
              autoComplete="given-name"
              invalid={Boolean(form.formState.errors.firstName)}
              {...form.register('firstName')}
            />
          </Field>

          <Field
            id="lastName"
            label={labels.lastName}
            hint={labels.optional}
            error={form.formState.errors.lastName?.message}
          >
            <Input id="lastName" autoComplete="family-name" {...form.register('lastName')} />
          </Field>
        </div>

        <Field
          id="email"
          label={labels.email}
          error={form.formState.errors.email?.message}
          required
        >
          <Input
            id="email"
            type="email"
            inputMode="email"
            autoComplete="email"
            autoCapitalize="none"
            spellCheck={false}
            dir="ltr"
            invalid={Boolean(form.formState.errors.email)}
            {...form.register('email')}
          />
        </Field>

        <Field
          id="phone"
          label={labels.phone}
          hint={labels.optional}
          error={form.formState.errors.phone?.message}
        >
          <Input
            id="phone"
            type="tel"
            inputMode="tel"
            autoComplete="tel"
            dir="ltr"
            placeholder="01xxxxxxxxx"
            invalid={Boolean(form.formState.errors.phone)}
            {...form.register('phone')}
          />
        </Field>

        <Field
          id="password"
          label={labels.password}
          error={form.formState.errors.password?.message}
          required
        >
          <Input
            id="password"
            type="password"
            autoComplete="new-password"
            dir="ltr"
            invalid={Boolean(form.formState.errors.password)}
            {...form.register('password')}
          />
        </Field>

        <label className="flex cursor-pointer items-start gap-2 text-sm">
          <input
            type="checkbox"
            className="mt-0.5 size-4 rounded accent-brand-600"
            {...form.register('marketingOptIn')}
          />
          {labels.marketingOptIn}
        </label>

        {error ? (
          <p role="alert" className="rounded-md bg-danger-500/10 p-3 text-sm text-danger-600">
            {error}
          </p>
        ) : null}

        <Button type="submit" size="lg" fullWidth loading={register.isPending}>
          {labels.submit}
        </Button>
      </form>

      <div className="my-5 flex items-center gap-3">
        <span className="h-px flex-1 bg-[var(--border)]" />
        <span className="text-xs text-[var(--muted-foreground)]">{labels.orContinueWith}</span>
        <span className="h-px flex-1 bg-[var(--border)]" />
      </div>

      <Button asChild variant="outline" size="lg" fullWidth>
        <a href="/api/v1/auth/oauth/google">{labels.continueWithGoogle}</a>
      </Button>

      <p className="mt-5 text-center text-sm text-[var(--muted-foreground)]">
        {labels.haveAccount}{' '}
        <Link href="/auth/login" className="font-medium text-brand-700 hover:underline">
          {labels.login}
        </Link>
      </p>
    </Card>
  );
}
