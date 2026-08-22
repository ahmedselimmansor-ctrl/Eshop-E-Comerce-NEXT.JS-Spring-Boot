'use client';

import { useMutation } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { ApiError, type LoginRequest } from '@eshop/api-client';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth-store';
import { Button, Card, Field, Input } from '@/components/ui/primitives';

/**
 * Operator sign-in.
 *
 * Uses the same endpoint as the storefront — there is one identity system, and staff are users with
 * roles. What differs is what happens next: an account with no staff permission is signed out again
 * rather than dropped into a console it cannot use.
 */
export default function LoginPage() {
  const router = useRouter();
  const setSession = useAuthStore((state) => state.setSession);
  const clear = useAuthStore((state) => state.clear);

  const [emailOrPhone, setEmailOrPhone] = useState('');
  const [password, setPassword] = useState('');
  const [rejected, setRejected] = useState(false);

  // A signed-in operator who lands here — usually by using the back button — belongs in the console.
  const user = useAuthStore((state) => state.user);
  useEffect(() => {
    if (user) router.replace('/dashboard');
  }, [user, router]);

  const signIn = useMutation({
    mutationFn: (body: LoginRequest) => api().auth.login(body),

    onSuccess: (response) => {
      /*
       * Staff-only gate.
       *
       * Every customer can authenticate against this endpoint, so a successful login proves
       * identity, not authorisation. Without any staff permission there is nothing here to show —
       * and letting them in to meet a wall of 403s would be worse than a clear refusal.
       */
      if (response.user.permissions.length === 0) {
        clear();
        setRejected(true);
        return;
      }

      setSession(response.user, response.tokens.accessToken);
      router.replace('/dashboard');
    },
  });

  const message = (() => {
    if (rejected) return 'This account does not have access to the admin console.';
    if (!signIn.error) return null;
    if (!(signIn.error instanceof ApiError)) return 'Something went wrong. Please try again.';

    // Deliberately identical for a wrong password and an unknown account: distinguishing them tells
    // an attacker which addresses are worth attacking.
    if (signIn.error.is('INVALID_CREDENTIALS')) return 'Incorrect email or password.';
    if (signIn.error.is('ACCOUNT_LOCKED')) return 'Account locked after too many attempts.';
    if (signIn.error.is('RATE_LIMITED')) return 'Too many attempts. Try again in a few minutes.';
    return signIn.error.detail || 'Something went wrong. Please try again.';
  })();

  return (
    <div className="flex min-h-dvh items-center justify-center p-4">
      <Card className="w-full max-w-sm p-6">
        <h1 className="text-lg font-semibold">Eshop Admin</h1>
        <p className="mt-1 text-xs text-[var(--muted-foreground)]">
          Sign in with your staff account.
        </p>

        <form
          className="mt-5 space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            setRejected(false);
            signIn.mutate({ emailOrPhone: emailOrPhone.trim(), password });
          }}
        >
          <Field id="emailOrPhone" label="Email or phone" required>
            <Input
              id="emailOrPhone"
              value={emailOrPhone}
              onChange={(event) => setEmailOrPhone(event.target.value)}
              autoComplete="username"
              autoFocus
              required
            />
          </Field>

          <Field id="password" label="Password" required>
            <Input
              id="password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
          </Field>

          {message ? (
            <p role="alert" className="text-xs text-danger-500">
              {message}
            </p>
          ) : null}

          <Button
            type="submit"
            className="w-full"
            loading={signIn.isPending}
            disabled={!emailOrPhone || !password}
          >
            Sign in
          </Button>
        </form>
      </Card>
    </div>
  );
}
