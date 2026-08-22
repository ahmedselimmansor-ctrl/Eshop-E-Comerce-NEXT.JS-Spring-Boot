'use client';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useFormatter, useTranslations } from 'next-intl';
import { AlertCircle, Check, Copy, Loader2, Store } from 'lucide-react';
import { useSearchParams } from 'next/navigation';
import { useEffect, useState } from 'react';
import { formatMoney, type PaymentInstruction } from '@eshop/api-client';
import { Link } from '@/i18n/routing';
import { Button } from '@/components/ui/button';
import { Card, Skeleton } from '@/components/ui/primitives';

/**
 * The post-checkout screen.
 *
 * Three outcomes share it, discriminated by provider:
 *
 * * **Paymob** — the customer was redirected to a hosted card page and has come back. Whether the
 *   payment settled is not yet known here, so this polls.
 * * **Fawry** — nothing has been paid yet by design. The customer needs the reference code, and
 *   this screen is the only place they will see it, so it has to be copyable and unambiguous.
 * * **Cash on delivery** — settled at the door, not here. Showing "awaiting payment" would be
 *   alarming and wrong.
 */
export function PaymentResult({ locale }: { locale: string }) {
  /*
   * Messages are read here rather than passed in from the page.
   *
   * A server component cannot hand a client component a function, and several of these strings are
   * parameterised (`fawryInstructions` takes an expiry). Calling `useTranslations` directly is both
   * the idiomatic next-intl approach and the only one that works across that boundary.
   */
  const t = useTranslations('checkout');
  const common = useTranslations('common');
  const format = useFormatter();

  const searchParams = useSearchParams();
  const queryClient = useQueryClient();

  const orderNumber = searchParams.get('order');
  const paymentId = searchParams.get('payment');

  const [copied, setCopied] = useState(false);

  const { data: payment, isLoading } = useQuery({
    queryKey: ['payment', paymentId],
    queryFn: () => browserApiPaymentStatus(paymentId as string),
    enabled: Boolean(paymentId),

    /**
     * Polls while the payment is still pending, then stops.
     *
     * The provider's callback may arrive seconds after the browser does, so a single read on mount
     * would show "awaiting payment" to a customer whose card has already been charged. Polling
     * stops as soon as the payment reaches a terminal state — and for Fawry it stops immediately,
     * because that customer has days to pay and no amount of polling will change the answer now.
     */
    refetchInterval: (query) => {
      const current = query.state.data;
      if (!current) return 2000;
      if (current.provider === 'FAWRY' || current.provider === 'COD') return false;
      return current.status === 'PENDING' ? 2000 : false;
    },

    // Bounded so a stuck payment does not poll forever in a background tab.
    refetchIntervalInBackground: false,
  });

  // The cart was converted server-side when the order was created; dropping the cached copy stops
  // the header badge from showing items that are now an order.
  useEffect(() => {
    if (payment) {
      void queryClient.invalidateQueries({ queryKey: ['cart'] });
    }
  }, [payment, queryClient]);

  if (!orderNumber) {
    return (
      <Card className="p-8 text-center">
        <p className="font-medium">{common('somethingWentWrong')}</p>
        <Button asChild variant="outline" className="mt-4">
          <Link href="/">{common('backToHome')}</Link>
        </Button>
      </Card>
    );
  }

  if (isLoading || !payment) {
    return <Skeleton className="h-80 w-full" />;
  }

  const settled = payment.status === 'CAPTURED';
  const failed =
    payment.status === 'FAILED' || payment.status === 'CANCELLED' || payment.status === 'EXPIRED';

  return (
    <Card className="overflow-hidden">
      <div className="flex flex-col items-center gap-3 border-b border-[var(--border)] p-8 text-center">
        {failed ? (
          <AlertCircle className="size-14 text-danger-500" aria-hidden />
        ) : settled || payment.provider === 'COD' ? (
          <div className="flex size-14 items-center justify-center rounded-full bg-success-500/15">
            <Check className="size-8 text-success-600" aria-hidden />
          </div>
        ) : payment.provider === 'FAWRY' ? (
          <Store className="size-14 text-brand-600" aria-hidden />
        ) : (
          <Loader2 className="size-14 animate-spin text-brand-600" aria-hidden />
        )}

        <h1 className="text-xl font-bold">
          {failed
            ? t('paymentFailed')
            : payment.provider === 'FAWRY' && !settled
              ? t('fawryReference')
              : t('orderPlaced')}
        </h1>

        <p className="text-sm text-[var(--muted-foreground)]">
          {t('orderNumber')}: <span className="numeric font-medium">{orderNumber}</span>
        </p>
      </div>

      <div className="space-y-5 p-6">
        {/* ---- Fawry: the reference is the whole point of this screen ---- */}
        {payment.provider === 'FAWRY' && payment.referenceCode && !settled ? (
          <div className="space-y-3">
            <div className="flex items-center justify-between gap-3 rounded-[var(--radius-control)] border-2 border-dashed border-brand-300 bg-brand-50 p-4">
              <span className="numeric text-2xl font-bold tracking-wider">
                {payment.referenceCode}
              </span>

              <Button
                variant="outline"
                size="sm"
                onClick={async () => {
                  await navigator.clipboard.writeText(payment.referenceCode as string);
                  setCopied(true);
                  setTimeout(() => setCopied(false), 2000);
                }}
              >
                {copied ? <Check aria-hidden /> : <Copy aria-hidden />}
                {copied ? t('copied') : t('copyReference')}
              </Button>
            </div>

            <p className="text-sm text-[var(--muted-foreground)]">
              {t('fawryInstructions', {
                expiry: payment.expiresAt
                  ? format.dateTime(new Date(payment.expiresAt), 'long')
                  : '',
              })}
            </p>
          </div>
        ) : null}

        {/* ---- Cash on delivery ---- */}
        {payment.provider === 'COD' ? (
          <p className="rounded-[var(--radius-control)] bg-success-500/10 p-4 text-sm">
            {t('codConfirmed')}
          </p>
        ) : null}

        {/* ---- Card, still settling ---- */}
        {payment.provider === 'PAYMOB' && !settled && !failed ? (
          <p
            className="flex items-center gap-2 rounded-[var(--radius-control)] bg-[var(--muted)] p-4 text-sm"
            aria-live="polite"
          >
            <Loader2 className="size-4 animate-spin" aria-hidden />
            {t('awaitingPayment')}
          </p>
        ) : null}

        {/* ---- Failed ---- */}
        {failed ? (
          <div className="space-y-3">
            {/* Stated explicitly. A customer who sees a failure assumes they were charged, and the
                support ticket that follows costs more than the sentence. */}
            <p className="rounded-[var(--radius-control)] bg-danger-500/10 p-4 text-sm">
              {t('paymentFailedHint')}
            </p>
            <Button asChild fullWidth>
              <Link href="/checkout">{t('retryPayment')}</Link>
            </Button>
          </div>
        ) : null}

        <div className="flex items-baseline justify-between border-t border-[var(--border)] pt-4">
          <span className="text-sm text-[var(--muted-foreground)]">{t('orderNumber')}</span>
          <span className="numeric font-semibold">{formatMoney(payment.amount, locale)}</span>
        </div>

        <div className="flex flex-col gap-2 sm:flex-row">
          <Button asChild variant="outline" className="flex-1">
            <Link href="/">{common('backToHome')}</Link>
          </Button>
          <Button asChild className="flex-1">
            <Link
              href={{
                pathname: '/account/orders/[orderNumber]',
                params: { orderNumber },
              }}
            >
              {t('viewOrder')}
            </Link>
          </Button>
        </div>
      </div>
    </Card>
  );
}

/**
 * Reads the payment's current state.
 *
 * The endpoint re-reads from the provider when the payment is not yet terminal, so this is the
 * recovery path for a webhook that never arrived — not merely a cache read.
 */
async function browserApiPaymentStatus(paymentId: string): Promise<PaymentInstruction> {
  const { browserApi } = await import('@/lib/api-browser');
  return browserApi().payments.status(paymentId);
}
