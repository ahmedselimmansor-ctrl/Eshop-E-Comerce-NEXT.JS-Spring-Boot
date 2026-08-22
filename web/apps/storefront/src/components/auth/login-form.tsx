'use client';

import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { ApiError, loginRequestSchema, type LoginRequest } from '@eshop/api-client';
import { Link, useRouter } from '@/i18n/routing';
import { Button } from '@/components/ui/button';
import { Card, Field, Input } from '@/components/ui/primitives';
import { browserApi } from '@/lib/api-browser';
import { useAuthStore } from '@/stores/auth-store';
import { clearSessionId, getOrCreateSessionId } from '@/lib/utils';

interface Labels {
  title: string;
  subtitle: string;
  emailOrPhone: string;
  password: string;
  rememberMe: string;
  forgotPassword: string;
  submit: string;
  noAccount: string;
  register: string;
  continueWithGoogle: string;
  orContinueWith: string;
  invalidCredentials: string;
  accountLocked: string;
  rateLimited: string;
  somethingWentWrong: string;
}

export function LoginForm({ labels }: { labels: Labels }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const queryClient = useQueryClient();
  const setSession = useAuthStore((state) => state.setSession);

  // Where the middleware wanted to send them before it bounced them here.
  const next = searchParams.get('next');

  const form = useForm<LoginRequest>({
    // The same schema the API validates against, so the two cannot drift apart.
    resolver: zodResolver(loginRequestSchema),
    defaultValues: { emailOrPhone: '', password: '', rememberMe: true },
  });

  const signIn = useMutation({
    mutationFn: (values: LoginRequest) => browserApi().auth.login(values),

    onSuccess: async (response) => {
      setSession(response.user, response.tokens.accessToken);

      // Folds anything added while browsing anonymously into the customer's cart. A failure here
      // is not worth blocking the sign-in over - they are signed in either way, and the merge is
      // retried on the next session restore.
      const sessionId = getOrCreateSessionId();
      if (sessionId) {
        try {
          await browserApi().cart.merge(sessionId);
          clearSessionId();
        } catch {
          /* the guest cart stays where it is */
        }
      }

      // Drops anything cached for the previous (anonymous) visitor before the authenticated views
      // render.
      queryClient.clear();

      router.replace(next && next.startsWith('/') ? (next as never) : '/');
      router.refresh();
    },
  });

  /**
   * Maps the failure to something a person can act on.
   *
   * Branches on the stable `code`, never on the message. A locked account and a wrong password are
   * genuinely different situations for the customer, and "invalid credentials" for both would send
   * someone to reset a password that was correct.
   */
  function errorMessage(): string | null {
    if (!(signIn.error instanceof ApiError)) {
      return signIn.error ? labels.somethingWentWrong : null;
    }
    if (signIn.error.is('ACCOUNT_LOCKED')) return labels.accountLocked;
    if (signIn.error.is('RATE_LIMITED')) return labels.rateLimited;
    if (signIn.error.is('INVALID_CREDENTIALS')) return labels.invalidCredentials;
    return signIn.error.detail || labels.somethingWentWrong;
  }

  const error = errorMessage();

  return (
    <Card className="p-6">
      <h1 className="text-xl font-bold">{labels.title}</h1>
      <p className="mt-1 text-sm text-[var(--muted-foreground)]">{labels.subtitle}</p>

      <form
        onSubmit={form.handleSubmit((values) => signIn.mutate(values))}
        className="mt-6 space-y-4"
        noValidate
      >
        <Field
          id="emailOrPhone"
          label={labels.emailOrPhone}
          error={form.formState.errors.emailOrPhone?.message}
          required
        >
          <Input
            id="emailOrPhone"
            // `username` rather than `email`: the field accepts a phone number too, and password
            // managers handle the generic hint correctly for both.
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            dir="auto"
            invalid={Boolean(form.formState.errors.emailOrPhone)}
            {...form.register('emailOrPhone')}
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
            autoComplete="current-password"
            dir="ltr"
            invalid={Boolean(form.formState.errors.password)}
            {...form.register('password')}
          />
        </Field>

        <div className="flex items-center justify-between gap-3">
          <label className="flex cursor-pointer items-center gap-2 text-sm">
            <input
              type="checkbox"
              className="size-4 rounded accent-brand-600"
              {...form.register('rememberMe')}
            />
            {labels.rememberMe}
          </label>

          <Link href="/auth/forgot-password" className="text-sm text-brand-700 hover:underline">
            {labels.forgotPassword}
          </Link>
        </div>

        {error ? (
          <p role="alert" className="rounded-md bg-danger-500/10 p-3 text-sm text-danger-600">
            {error}
          </p>
        ) : null}

        <Button type="submit" size="lg" fullWidth loading={signIn.isPending}>
          {labels.submit}
        </Button>
      </form>

      <div className="my-5 flex items-center gap-3">
        <span className="h-px flex-1 bg-[var(--border)]" />
        <span className="text-xs text-[var(--muted-foreground)]">{labels.orContinueWith}</span>
        <span className="h-px flex-1 bg-[var(--border)]" />
      </div>

      {/*
       * A plain anchor, not the locale-aware Link: this is a full-page navigation to the API's
       * OAuth entry point, which lives outside the Next.js router.
       */}
      <Button asChild variant="outline" size="lg" fullWidth>
        <a
          href={`/api/v1/auth/oauth/google${next ? `?next=${encodeURIComponent(next)}` : ''}`}
        >
          {labels.continueWithGoogle}
        </a>
      </Button>

      <p className="mt-5 text-center text-sm text-[var(--muted-foreground)]">
        {labels.noAccount}{' '}
        <Link href="/auth/register" className="font-medium text-brand-700 hover:underline">
          {labels.register}
        </Link>
      </p>
    </Card>
  );
}
