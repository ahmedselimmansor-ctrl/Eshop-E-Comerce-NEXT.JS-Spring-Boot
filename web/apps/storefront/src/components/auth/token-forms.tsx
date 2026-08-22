'use client';

import { useMutation } from '@tanstack/react-query';
import { CheckCircle2, MailCheck, XCircle } from 'lucide-react';
import { useSearchParams } from 'next/navigation';
import { useEffect, useState } from 'react';
import { ApiError } from '@eshop/api-client';
import { Link, useRouter } from '@/i18n/routing';
import { Button } from '@/components/ui/button';
import { Card, Field, Input } from '@/components/ui/primitives';
import { browserApi } from '@/lib/api-browser';
import { useAuthStore } from '@/stores/auth-store';

/**
 * The three token-driven auth screens, grouped because each is one field and one call and would
 * otherwise be three near-identical files.
 */

// ------------------------------------------------------------ forgot password --

/**
 * Requests a reset link.
 *
 * Always reports success, whether or not the address is registered - the API returns 202 either
 * way. A screen that said "no account with that email" would be an enumeration oracle, and the
 * wording below is deliberately conditional ("if that address is registered") so it is honest
 * rather than merely evasive.
 */
export function ForgotPasswordForm({
  labels,
}: {
  labels: {
    title: string;
    subtitle: string;
    email: string;
    submit: string;
    sent: string;
    backToLogin: string;
    somethingWentWrong: string;
  };
}) {
  const [email, setEmail] = useState('');

  const request = useMutation({
    mutationFn: (address: string) => browserApi().auth.forgotPassword(address),
  });

  if (request.isSuccess) {
    return (
      <Card className="flex flex-col items-center gap-3 p-8 text-center">
        <MailCheck className="size-12 text-brand-600" aria-hidden />
        <p className="text-sm">{labels.sent}</p>
        <Button asChild variant="outline" className="mt-2">
          <Link href="/auth/login">{labels.backToLogin}</Link>
        </Button>
      </Card>
    );
  }

  return (
    <Card className="p-6">
      <h1 className="text-xl font-bold">{labels.title}</h1>
      <p className="mt-1 text-sm text-[var(--muted-foreground)]">{labels.subtitle}</p>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (email.trim()) request.mutate(email.trim());
        }}
        className="mt-6 space-y-4"
        noValidate
      >
        <Field id="email" label={labels.email} required>
          <Input
            id="email"
            type="email"
            inputMode="email"
            autoComplete="email"
            dir="ltr"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </Field>

        {request.isError ? (
          <p role="alert" className="rounded-md bg-danger-500/10 p-3 text-sm text-danger-600">
            {labels.somethingWentWrong}
          </p>
        ) : null}

        <Button type="submit" size="lg" fullWidth loading={request.isPending}>
          {labels.submit}
        </Button>
      </form>

      <p className="mt-5 text-center">
        <Link href="/auth/login" className="text-sm text-brand-700 hover:underline">
          {labels.backToLogin}
        </Link>
      </p>
    </Card>
  );
}

// ------------------------------------------------------------- reset password --

export function ResetPasswordForm({
  labels,
}: {
  labels: {
    title: string;
    newPassword: string;
    confirmPassword: string;
    submit: string;
    success: string;
    login: string;
    mismatch: string;
    invalidLink: string;
    somethingWentWrong: string;
  };
}) {
  const searchParams = useSearchParams();
  const token = searchParams.get('token');

  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [mismatch, setMismatch] = useState(false);

  const reset = useMutation({
    mutationFn: (newPassword: string) =>
      browserApi().auth.resetPassword(token as string, newPassword),
  });

  if (!token) {
    return (
      <Card className="flex flex-col items-center gap-3 p-8 text-center">
        <XCircle className="size-12 text-danger-500" aria-hidden />
        <p className="text-sm">{labels.invalidLink}</p>
      </Card>
    );
  }

  if (reset.isSuccess) {
    return (
      <Card className="flex flex-col items-center gap-3 p-8 text-center">
        <CheckCircle2 className="size-12 text-success-600" aria-hidden />
        <p className="text-sm">{labels.success}</p>
        <Button asChild className="mt-2">
          <Link href="/auth/login">{labels.login}</Link>
        </Button>
      </Card>
    );
  }

  const failed = reset.error instanceof ApiError;
  const expired =
    reset.error instanceof ApiError && reset.error.is('TOKEN_EXPIRED', 'TOKEN_INVALID');

  return (
    <Card className="p-6">
      <h1 className="text-xl font-bold">{labels.title}</h1>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (password !== confirmation) {
            setMismatch(true);
            return;
          }
          setMismatch(false);
          reset.mutate(password);
        }}
        className="mt-6 space-y-4"
        noValidate
      >
        <Field id="newPassword" label={labels.newPassword} required>
          <Input
            id="newPassword"
            type="password"
            autoComplete="new-password"
            dir="ltr"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </Field>

        <Field
          id="confirmPassword"
          label={labels.confirmPassword}
          error={mismatch ? labels.mismatch : undefined}
          required
        >
          <Input
            id="confirmPassword"
            type="password"
            autoComplete="new-password"
            dir="ltr"
            invalid={mismatch}
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
          />
        </Field>

        {failed ? (
          <p role="alert" className="rounded-md bg-danger-500/10 p-3 text-sm text-danger-600">
            {expired
              ? labels.invalidLink
              : (reset.error as ApiError).detail || labels.somethingWentWrong}
          </p>
        ) : null}

        <Button type="submit" size="lg" fullWidth loading={reset.isPending}>
          {labels.submit}
        </Button>
      </form>
    </Card>
  );
}

// -------------------------------------------------------------- verify email --

/**
 * Consumes the verification token from the emailed link.
 *
 * Runs on mount rather than behind a button: the customer already expressed intent by clicking the
 * link in their inbox, and asking them to confirm again is friction with no purpose.
 */
export function VerifyEmailView({
  labels,
}: {
  labels: { title: string; success: string; failed: string; continueShopping: string; loading: string };
}) {
  const searchParams = useSearchParams();
  const token = searchParams.get('token');

  const verify = useMutation({
    mutationFn: (value: string) => browserApi().auth.verifyEmail(value),
  });

  useEffect(() => {
    if (token) verify.mutate(token);
    // Deliberately runs once, on the token. `verify` is stable enough for this and including it
    // would re-fire the mutation on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  return (
    <Card className="flex flex-col items-center gap-3 p-8 text-center">
      {!token || verify.isError ? (
        <>
          <XCircle className="size-12 text-danger-500" aria-hidden />
          <p className="text-sm">{labels.failed}</p>
        </>
      ) : verify.isSuccess ? (
        <>
          <CheckCircle2 className="size-12 text-success-600" aria-hidden />
          <p className="text-sm">{labels.success}</p>
        </>
      ) : (
        <p className="text-sm text-[var(--muted-foreground)]" aria-live="polite">
          {labels.loading}
        </p>
      )}

      <Button asChild variant="outline" className="mt-2">
        <Link href="/">{labels.continueShopping}</Link>
      </Button>
    </Card>
  );
}

// ------------------------------------------------------------- OAuth callback --

/**
 * Redeems the single-use code the OAuth callback put in the URL.
 *
 * The API deliberately redirects here with a short-lived code rather than with tokens: anything in
 * a query string lands in browser history, in the `Referer` of the next request, and in every proxy
 * log along the way. This exchanges it over POST for the real pair.
 */
export function OAuthCallbackView({
  labels,
}: {
  labels: { loading: string; failed: string; login: string };
}) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const setSession = useAuthStore((state) => state.setSession);

  const code = searchParams.get('code');
  const next = searchParams.get('next');

  const exchange = useMutation({
    mutationFn: (value: string) => browserApi().auth.exchangeOAuthCode(value),
    onSuccess: (response) => {
      setSession(response.user, response.tokens.accessToken);
      router.replace(next && next.startsWith('/') ? (next as never) : '/');
      router.refresh();
    },
  });

  useEffect(() => {
    if (code) exchange.mutate(code);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code]);

  if (!code || exchange.isError) {
    return (
      <Card className="flex flex-col items-center gap-3 p-8 text-center">
        <XCircle className="size-12 text-danger-500" aria-hidden />
        <p className="text-sm">{labels.failed}</p>
        <Button asChild variant="outline" className="mt-2">
          <Link href="/auth/login">{labels.login}</Link>
        </Button>
      </Card>
    );
  }

  return (
    <Card className="p-8 text-center">
      <p className="text-sm text-[var(--muted-foreground)]" aria-live="polite">
        {labels.loading}
      </p>
    </Card>
  );
}
