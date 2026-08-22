'use client';

import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import {
  ApiError,
  updateProfileRequestSchema,
  type UpdateProfileRequest,
} from '@eshop/api-client';
import { Button } from '@/components/ui/button';
import { Card, Field, Input, Select, Separator, Skeleton } from '@/components/ui/primitives';
import { browserApi } from '@/lib/api-browser';
import { useAuthStore } from '@/stores/auth-store';

interface Labels {
  profile: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  language: string;
  arabic: string;
  english: string;
  marketingOptIn: string;
  save: string;
  saved: string;
  security: string;
  changePassword: string;
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
  passwordChanged: string;
  mismatch: string;
  optional: string;
  somethingWentWrong: string;
}

export function ProfileForm({ labels }: { labels: Labels }) {
  const queryClient = useQueryClient();
  const setUser = useAuthStore((state) => state.setUser);

  const { data: user, isLoading } = useQuery({
    queryKey: ['me'],
    queryFn: () => browserApi().account.me(),
  });

  const form = useForm<UpdateProfileRequest>({
    resolver: zodResolver(updateProfileRequestSchema),
    defaultValues: { firstName: '', lastName: '', phone: '', locale: 'ar', marketingOptIn: false },
  });

  // Populated once the profile loads. `reset` rather than per-field `setValue` so the form's dirty
  // state starts clean and the save button is not enabled before anything was edited.
  useEffect(() => {
    if (user) {
      form.reset({
        firstName: user.firstName,
        lastName: user.lastName ?? '',
        phone: user.phone ?? '',
        locale: user.locale === 'en' ? 'en' : 'ar',
        marketingOptIn: user.marketingOptIn,
      });
    }
  }, [user, form]);

  const save = useMutation({
    mutationFn: (values: UpdateProfileRequest) =>
      browserApi().account.updateProfile({
        ...values,
        phone: values.phone?.trim() ? values.phone : undefined,
        lastName: values.lastName?.trim() ? values.lastName : undefined,
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(['me'], updated);
      // Keeps the header's name in step with the form without a second fetch.
      setUser(updated);
      form.reset(form.getValues());
    },
    onError: (error) => {
      if (error instanceof ApiError) {
        for (const [field, message] of Object.entries(error.fieldErrors())) {
          form.setError(field as keyof UpdateProfileRequest, { message });
        }
      }
    },
  });

  if (isLoading || !user) return <Skeleton className="h-96 w-full" />;

  return (
    <div className="space-y-4">
      <Card className="p-5">
        <h2 className="font-semibold">{labels.profile}</h2>

        <form
          onSubmit={form.handleSubmit((values) => save.mutate(values))}
          className="mt-4 space-y-4"
          noValidate
        >
          <div className="grid gap-4 sm:grid-cols-2">
            <Field
              id="firstName"
              label={labels.firstName}
              error={form.formState.errors.firstName?.message}
              required
            >
              <Input id="firstName" autoComplete="given-name" {...form.register('firstName')} />
            </Field>

            <Field id="lastName" label={labels.lastName} hint={labels.optional}>
              <Input id="lastName" autoComplete="family-name" {...form.register('lastName')} />
            </Field>
          </div>

          {/* Email is shown but not editable: changing the address that receives password-reset
              links is an account-takeover primitive, so it runs through its own verify-then-swap
              flow rather than a text field on a settings page. */}
          <Field id="email" label={labels.email}>
            <Input id="email" value={user.email} disabled dir="ltr" readOnly />
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
              dir="ltr"
              autoComplete="tel"
              invalid={Boolean(form.formState.errors.phone)}
              {...form.register('phone')}
            />
          </Field>

          <Field id="locale" label={labels.language}>
            <Select id="locale" {...form.register('locale')}>
              <option value="ar">{labels.arabic}</option>
              <option value="en">{labels.english}</option>
            </Select>
          </Field>

          <label className="flex cursor-pointer items-start gap-2 text-sm">
            <input
              type="checkbox"
              className="mt-0.5 size-4 rounded accent-brand-600"
              {...form.register('marketingOptIn')}
            />
            {labels.marketingOptIn}
          </label>

          {save.isError && !(save.error instanceof ApiError && save.error.is('VALIDATION_ERROR')) ? (
            <p role="alert" className="rounded-md bg-danger-500/10 p-3 text-sm text-danger-600">
              {labels.somethingWentWrong}
            </p>
          ) : null}

          <div className="flex items-center gap-3">
            <Button
              type="submit"
              // Disabled until something actually changed - a save button that is always live
              // invites pointless writes.
              disabled={!form.formState.isDirty}
              loading={save.isPending}
            >
              {labels.save}
            </Button>

            {save.isSuccess && !form.formState.isDirty ? (
              <span className="flex items-center gap-1 text-sm text-success-600">
                <Check className="size-4" aria-hidden />
                {labels.saved}
              </span>
            ) : null}
          </div>
        </form>
      </Card>

      <PasswordSection labels={labels} />
    </div>
  );
}

/**
 * Password change.
 *
 * Requires the current password even though the caller is already authenticated: a stolen access
 * token should not be enough to lock the real owner out of their own account. On success the API
 * revokes every other session, which is why the confirmation says so.
 */
function PasswordSection({ labels }: { labels: Labels }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [mismatch, setMismatch] = useState(false);

  const change = useMutation({
    mutationFn: () => browserApi().account.changePassword(current, next),
    onSuccess: () => {
      setCurrent('');
      setNext('');
      setConfirmation('');
    },
  });

  const error = change.error instanceof ApiError ? change.error.detail : null;

  return (
    <Card className="p-5">
      <h2 className="font-semibold">{labels.security}</h2>
      <Separator className="my-4" />

      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (next !== confirmation) {
            setMismatch(true);
            return;
          }
          setMismatch(false);
          change.mutate();
        }}
        className="space-y-4"
        noValidate
      >
        <Field id="currentPassword" label={labels.currentPassword} required>
          <Input
            id="currentPassword"
            type="password"
            autoComplete="current-password"
            dir="ltr"
            value={current}
            onChange={(event) => setCurrent(event.target.value)}
          />
        </Field>

        <Field id="newPassword" label={labels.newPassword} required>
          <Input
            id="newPassword"
            type="password"
            autoComplete="new-password"
            dir="ltr"
            value={next}
            onChange={(event) => setNext(event.target.value)}
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

        {error ? (
          <p role="alert" className="rounded-md bg-danger-500/10 p-3 text-sm text-danger-600">
            {error}
          </p>
        ) : null}

        {change.isSuccess ? (
          <p className="rounded-md bg-success-500/10 p-3 text-sm text-success-600">
            {labels.passwordChanged}
          </p>
        ) : null}

        <Button
          type="submit"
          variant="outline"
          disabled={!current || !next}
          loading={change.isPending}
        >
          {labels.changePassword}
        </Button>
      </form>
    </Card>
  );
}
