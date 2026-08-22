'use client';

import { useMutation } from '@tanstack/react-query';
import { PackageSearch } from 'lucide-react';
import { useState } from 'react';
import { ApiError, formatMoney, type Order } from '@eshop/api-client';
import { Button } from '@/components/ui/button';
import { Badge, Card, Field, Input, Separator } from '@/components/ui/primitives';
import { browserApi } from '@/lib/api-browser';
import { formatRelativeTime } from '@/lib/utils';

interface Labels {
  title: string;
  hint: string;
  orderNumber: string;
  email: string;
  submit: string;
  notFound: string;
  somethingWentWrong: string;
  status: string;
}

/**
 * Guest order tracking.
 *
 * Requires the order number **and** the email it was placed with. The number alone is short and
 * sequential enough to guess; pairing it with the email means a guessed number reveals nothing,
 * which is why the API scopes the lookup the same way.
 */
export function TrackOrderForm({ locale, labels }: { locale: string; labels: Labels }) {
  const [orderNumber, setOrderNumber] = useState('');
  const [email, setEmail] = useState('');

  const track = useMutation({
    mutationFn: () => browserApi().orders.track(orderNumber.trim(), email.trim()),
  });

  const order: Order | undefined = track.data;

  const errorMessage =
    track.error instanceof ApiError
      ? track.error.status === 404
        ? labels.notFound
        : track.error.detail
      : track.error
        ? labels.somethingWentWrong
        : null;

  return (
    <div className="space-y-4">
      <Card className="p-6">
        <h1 className="text-xl font-bold">{labels.title}</h1>
        <p className="mt-1 text-sm text-[var(--muted-foreground)]">{labels.hint}</p>

        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (orderNumber.trim() && email.trim()) track.mutate();
          }}
          className="mt-6 space-y-4"
          noValidate
        >
          <Field id="orderNumber" label={labels.orderNumber} required>
            <Input
              id="orderNumber"
              value={orderNumber}
              onChange={(event) => setOrderNumber(event.target.value.toUpperCase())}
              placeholder="EG-260821-00042"
              className="numeric"
              autoCapitalize="characters"
              spellCheck={false}
            />
          </Field>

          <Field id="email" label={labels.email} required>
            <Input
              id="email"
              type="email"
              inputMode="email"
              dir="ltr"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </Field>

          {errorMessage ? (
            <p role="alert" className="rounded-md bg-danger-500/10 p-3 text-sm text-danger-600">
              {errorMessage}
            </p>
          ) : null}

          <Button type="submit" size="lg" fullWidth loading={track.isPending}>
            <PackageSearch aria-hidden />
            {labels.submit}
          </Button>
        </form>
      </Card>

      {order ? (
        <Card className="p-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <span className="numeric font-semibold">{order.orderNumber}</span>
            <Badge tone={order.status === 'DELIVERED' ? 'success' : 'brand'}>{order.status}</Badge>
          </div>

          <Separator className="my-4" />

          <ol className="space-y-3">
            {order.timeline.map((entry, index) => (
              <li key={`${entry.status}-${index}`} className="text-sm">
                <span className="font-medium">{entry.status}</span>
                <span className="ms-2 text-xs text-[var(--muted-foreground)]">
                  {formatRelativeTime(entry.at, locale)}
                </span>
              </li>
            ))}
          </ol>

          <Separator className="my-4" />

          <div className="flex items-baseline justify-between">
            <span className="text-sm text-[var(--muted-foreground)]">{labels.status}</span>
            <span className="numeric font-semibold">
              {formatMoney(order.totals.grandTotal, locale)}
            </span>
          </div>
        </Card>
      ) : null}
    </div>
  );
}
