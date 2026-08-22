'use client';

import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { Banknote, CreditCard, Smartphone, Store } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ApiError,
  formatMoney,
  newIdempotencyKey,
  type CheckoutRequest,
  type PaymentMethod,
  type PaymentProviderType,
  type ShippingOption,
} from '@eshop/api-client';
import { useRouter } from '@/i18n/routing';
import { Button } from '@/components/ui/button';
import { Card, Field, Input, Select, Separator, Skeleton, Textarea } from '@/components/ui/primitives';
import { browserApi } from '@/lib/api-browser';
import { useAuthStore } from '@/stores/auth-store';
import { EGYPT_GOVERNORATES, cn } from '@/lib/utils';

/**
 * A payment option, holding message *keys* rather than resolved strings.
 *
 * The component looks each one up itself. Passing labels in from the page was the original design
 * and does not work: several of them are parameterised, and a server component cannot hand a client
 * component a function — React refuses to serialise it across the boundary.
 */
interface PaymentOption {
  provider: PaymentProviderType;
  method: PaymentMethod;
  labelKey: 'payWithCard' | 'payWithWallet' | 'payWithFawry' | 'payWithCod';
  hintKey: 'payWithCardHint' | 'payWithWalletHint' | 'payWithFawryHint' | 'payWithCodHint';
  icon: typeof CreditCard;
}

const PAYMENT_OPTIONS: PaymentOption[] = [
  {
    provider: 'PAYMOB',
    method: 'CARD',
    labelKey: 'payWithCard',
    hintKey: 'payWithCardHint',
    icon: CreditCard,
  },
  {
    provider: 'PAYMOB',
    method: 'WALLET',
    labelKey: 'payWithWallet',
    hintKey: 'payWithWalletHint',
    icon: Smartphone,
  },
  {
    provider: 'FAWRY',
    method: 'KIOSK',
    labelKey: 'payWithFawry',
    hintKey: 'payWithFawryHint',
    icon: Store,
  },
  {
    provider: 'COD',
    method: 'COD',
    labelKey: 'payWithCod',
    hintKey: 'payWithCodHint',
    icon: Banknote,
  },
];

/**
 * The checkout flow.
 *
 * One page rather than a wizard: every field fits on a phone screen with scrolling, and a
 * multi-step wizard adds a click between each decision and the total, which is the number the
 * shopper is actually watching. Totals re-quote from the server whenever the address, method, or
 * payment choice changes.
 */
export function CheckoutFlow({ locale }: { locale: string }) {
  const t = useTranslations('checkout');
  const address = useTranslations('address');
  const cartText = useTranslations('cart');
  const common = useTranslations('common');

  const router = useRouter();
  const user = useAuthStore((state) => state.user);

  const [governorate, setGovernorate] = useState('');
  const [addressId, setAddressId] = useState<string | null>(null);
  const [shippingMethodId, setShippingMethodId] = useState<string | null>(null);
  const [payment, setPayment] = useState<PaymentOption>(PAYMENT_OPTIONS[0] as PaymentOption);
  const [note, setNote] = useState('');
  const [guestEmail, setGuestEmail] = useState('');
  const [guestPhone, setGuestPhone] = useState('');

  const [inlineAddress, setInlineAddress] = useState({
    recipientName: '',
    phone: '',
    city: '',
    area: '',
    street: '',
    building: '',
    floor: '',
    apartment: '',
    landmark: '',
  });

  /**
   * The idempotency key is minted **once per checkout attempt**, not per request.
   *
   * That is the whole point: if the response is lost and the customer presses "Place order" again,
   * the retry carries the same key and the server returns the original order instead of creating a
   * second one. A key generated inside the mutation would give up that guarantee precisely when it
   * is needed.
   */
  const idempotencyKey = useRef(newIdempotencyKey());

  const { data: cart } = useQuery({
    queryKey: ['cart'],
    queryFn: () => browserApi().cart.get(),
  });

  const { data: addresses } = useQuery({
    queryKey: ['addresses'],
    queryFn: () => browserApi().account.addresses(),
    enabled: Boolean(user),
  });

  // Preselects the saved default so a returning customer has nothing to fill in.
  useEffect(() => {
    if (!addresses || addressId) return;

    const preferred = addresses.find((entry) => entry.defaultShipping) ?? addresses[0];
    if (preferred) {
      setAddressId(preferred.id);
      setGovernorate(preferred.governorate);
    }
  }, [addresses, addressId]);

  const { data: quote, isFetching: quoting } = useQuery({
    queryKey: ['checkout-quote', governorate, shippingMethodId, payment.provider, cart?.coupon?.code],
    queryFn: () =>
      browserApi().checkout.quote({
        governorate,
        shippingMethodId: shippingMethodId ?? undefined,
        couponCode: cart?.coupon?.code,
        paymentProvider: payment.provider,
      }),
    // Nothing to quote until a destination is known — shipping cost is the whole point of the call.
    enabled: governorate.length > 0 && Boolean(cart) && cart!.items.length > 0,
  });

  const shippingOptions: ShippingOption[] = quote?.shippingOptions ?? [];

  // Defaults to the cheapest option once the destination is known.
  useEffect(() => {
    if (shippingOptions.length === 0) return;
    if (shippingMethodId && shippingOptions.some((option) => option.id === shippingMethodId)) return;

    const cheapest = [...shippingOptions].sort(
      (a, b) => Number.parseFloat(a.cost.amount) - Number.parseFloat(b.cost.amount),
    )[0];
    if (cheapest) setShippingMethodId(cheapest.id);
  }, [shippingOptions, shippingMethodId]);

  const selectedShipping = shippingOptions.find((option) => option.id === shippingMethodId);

  // Cash on delivery depends on the courier, not just the store — a same-day bike courier will not
  // carry cash. Falls back to card if the current choice becomes invalid.
  const codAvailable = selectedShipping?.supportsCod ?? false;

  useEffect(() => {
    if (payment.provider === 'COD' && !codAvailable) {
      setPayment(PAYMENT_OPTIONS[0] as PaymentOption);
    }
  }, [codAvailable, payment.provider]);

  const placeOrder = useMutation({
    mutationFn: () => {
      const body: CheckoutRequest = {
        shippingMethodId: shippingMethodId as string,
        paymentProvider: payment.provider,
        paymentMethod: payment.method,
        couponCode: cart?.coupon?.code,
        customerNote: note || undefined,
        ...(addressId
          ? { shippingAddressId: addressId }
          : {
              shippingAddress: {
                ...inlineAddress,
                governorate,
                countryCode: 'EG',
              },
            }),
        ...(user ? {} : { guestEmail, guestPhone }),
      } as CheckoutRequest;

      return browserApi().checkout.placeOrder(body, idempotencyKey.current);
    },

    onSuccess: (placed) => {
      // Routed to the callback screen rather than showing the result inline: Paymob needs to load
      // an iframe, Fawry needs to show a reference code, and COD needs a confirmation — all three
      // are a different screen, and all three must survive a refresh.
      router.push({
        pathname: '/checkout/callback',
        query: {
          order: placed.order.orderNumber,
          payment: placed.payment.paymentId,
        },
      });
    },
  });

  const error = placeOrder.error instanceof ApiError ? placeOrder.error : null;

  const canSubmit = useMemo(() => {
    if (!cart || cart.items.length === 0) return false;
    if (!shippingMethodId || !governorate) return false;
    if (!user && (!guestEmail || !guestPhone)) return false;
    if (!addressId && (!inlineAddress.recipientName || !inlineAddress.street || !inlineAddress.city)) {
      return false;
    }
    return true;
  }, [cart, shippingMethodId, governorate, user, guestEmail, guestPhone, addressId, inlineAddress]);

  if (!cart) {
    return <Skeleton className="h-96 w-full" />;
  }

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_340px] lg:items-start">
      <div className="space-y-5">
        {/* Guest contact */}
        {!user ? (
          <Card className="space-y-4 p-5">
            <h2 className="font-semibold">{t('guestEmail')}</h2>

            <Field id="guest-email" label={t('guestEmail')} hint={t('guestEmailHint')} required>
              <Input
                id="guest-email"
                type="email"
                autoComplete="email"
                value={guestEmail}
                onChange={(event) => setGuestEmail(event.target.value)}
              />
            </Field>

            <Field id="guest-phone" label={t('guestPhone')} hint={t('guestPhoneHint')} required>
              <Input
                id="guest-phone"
                type="tel"
                inputMode="tel"
                autoComplete="tel"
                dir="ltr"
                value={guestPhone}
                onChange={(event) => setGuestPhone(event.target.value)}
              />
            </Field>
          </Card>
        ) : null}

        {/* Address */}
        <Card className="space-y-4 p-5">
          <h2 className="font-semibold">{t('shippingAddress')}</h2>

          {addresses && addresses.length > 0 ? (
            <div className="space-y-2">
              {addresses.map((entry) => (
                <label
                  key={entry.id}
                  className={cn(
                    'flex cursor-pointer gap-3 rounded-[var(--radius-control)] border p-3 text-sm',
                    addressId === entry.id
                      ? 'border-brand-600 bg-brand-50'
                      : 'border-[var(--border)] hover:border-brand-300',
                  )}
                >
                  <input
                    type="radio"
                    name="address"
                    checked={addressId === entry.id}
                    onChange={() => {
                      setAddressId(entry.id);
                      setGovernorate(entry.governorate);
                      // A new destination invalidates the chosen courier.
                      setShippingMethodId(null);
                    }}
                    className="mt-0.5 accent-brand-600"
                  />
                  <span className="flex-1">
                    <span className="block font-medium">{entry.recipientName}</span>
                    <span className="block text-xs text-[var(--muted-foreground)]">
                      {entry.formatted}
                    </span>
                  </span>
                </label>
              ))}

              <label className="flex cursor-pointer items-center gap-3 rounded-[var(--radius-control)] border border-dashed border-[var(--border)] p-3 text-sm">
                <input
                  type="radio"
                  name="address"
                  checked={addressId === null}
                  onChange={() => setAddressId(null)}
                  className="accent-brand-600"
                />
                {t('newAddress')}
              </label>
            </div>
          ) : null}

          {addressId === null ? (
            <div className="grid gap-4 sm:grid-cols-2">
              <Field id="recipient" label={address('recipientName')} required>
                <Input
                  id="recipient"
                  autoComplete="name"
                  value={inlineAddress.recipientName}
                  onChange={(event) =>
                    setInlineAddress((current) => ({ ...current, recipientName: event.target.value }))
                  }
                />
              </Field>

              <Field id="phone" label={t('guestPhone')} required>
                <Input
                  id="phone"
                  type="tel"
                  inputMode="tel"
                  dir="ltr"
                  autoComplete="tel"
                  value={inlineAddress.phone}
                  onChange={(event) =>
                    setInlineAddress((current) => ({ ...current, phone: event.target.value }))
                  }
                />
              </Field>

              <Field id="governorate" label={address('governorate')} required>
                <Select
                  id="governorate"
                  value={governorate}
                  onChange={(event) => {
                    setGovernorate(event.target.value);
                    setShippingMethodId(null);
                  }}
                >
                  <option value="">{address('selectGovernorate')}</option>
                  {EGYPT_GOVERNORATES.map((name) => (
                    <option key={name} value={name}>
                      {name}
                    </option>
                  ))}
                </Select>
              </Field>

              <Field id="city" label={address('city')} required>
                <Input
                  id="city"
                  value={inlineAddress.city}
                  onChange={(event) =>
                    setInlineAddress((current) => ({ ...current, city: event.target.value }))
                  }
                />
              </Field>

              <Field id="street" label={address('street')} required>
                <Input
                  id="street"
                  autoComplete="street-address"
                  value={inlineAddress.street}
                  onChange={(event) =>
                    setInlineAddress((current) => ({ ...current, street: event.target.value }))
                  }
                />
              </Field>

              <Field id="landmark" label={address('landmark')} hint={address('landmarkPlaceholder')}>
                <Input
                  id="landmark"
                  value={inlineAddress.landmark}
                  onChange={(event) =>
                    setInlineAddress((current) => ({ ...current, landmark: event.target.value }))
                  }
                />
              </Field>
            </div>
          ) : null}
        </Card>

        {/* Delivery method */}
        {governorate ? (
          <Card className="space-y-3 p-5">
            <h2 className="font-semibold">{t('shippingMethod')}</h2>

            {quoting && shippingOptions.length === 0 ? (
              <Skeleton className="h-20 w-full" />
            ) : shippingOptions.length === 0 ? (
              <p className="text-sm text-danger-500">{common('somethingWentWrong')}</p>
            ) : (
              shippingOptions.map((option) => (
                <label
                  key={option.id}
                  className={cn(
                    'flex cursor-pointer items-start gap-3 rounded-[var(--radius-control)] border p-3 text-sm',
                    shippingMethodId === option.id
                      ? 'border-brand-600 bg-brand-50'
                      : 'border-[var(--border)] hover:border-brand-300',
                  )}
                >
                  <input
                    type="radio"
                    name="shipping"
                    checked={shippingMethodId === option.id}
                    onChange={() => setShippingMethodId(option.id)}
                    className="mt-0.5 accent-brand-600"
                  />
                  <span className="flex-1">
                    <span className="block font-medium">{option.name}</span>
                    {option.estimatedDaysMin !== null && option.estimatedDaysMax !== null ? (
                      <span className="block text-xs text-[var(--muted-foreground)]">
                        {t('estimatedDelivery', {
                          min: option.estimatedDaysMin ?? 0,
                          max: option.estimatedDaysMax ?? 0,
                        })}
                      </span>
                    ) : null}
                  </span>
                  <span className="numeric shrink-0 font-medium">
                    {option.free ? t('free') : formatMoney(option.cost, locale)}
                  </span>
                </label>
              ))
            )}
          </Card>
        ) : null}

        {/* Payment */}
        <Card className="space-y-3 p-5">
          <h2 className="font-semibold">{t('paymentMethod')}</h2>

          {PAYMENT_OPTIONS.map((option) => {
            const disabled = option.provider === 'COD' && !codAvailable;
            const Icon = option.icon;
            const selected =
              payment.provider === option.provider && payment.method === option.method;

            return (
              <label
                key={`${option.provider}-${option.method}`}
                className={cn(
                  'flex cursor-pointer items-start gap-3 rounded-[var(--radius-control)] border p-3 text-sm',
                  selected ? 'border-brand-600 bg-brand-50' : 'border-[var(--border)]',
                  disabled && 'cursor-not-allowed opacity-50',
                )}
              >
                <input
                  type="radio"
                  name="payment"
                  checked={selected}
                  disabled={disabled}
                  onChange={() => setPayment(option)}
                  className="mt-0.5 accent-brand-600"
                />
                <Icon className="mt-0.5 size-5 shrink-0 text-brand-600" aria-hidden />
                <span className="flex-1">
                  <span className="block font-medium">{t(option.labelKey)}</span>
                  <span className="block text-xs text-[var(--muted-foreground)]">
                    {disabled ? t('codNotAvailable') : t(option.hintKey)}
                  </span>
                </span>
              </label>
            );
          })}
        </Card>

        <Card className="p-5">
          <Field id="note" label={t('customerNote')}>
            <Textarea
              id="note"
              value={note}
              maxLength={500}
              placeholder={t('customerNotePlaceholder')}
              onChange={(event) => setNote(event.target.value)}
            />
          </Field>
        </Card>
      </div>

      {/* Summary */}
      <Card className="lg:sticky lg:top-[calc(var(--spacing-header)+1rem)]">
        <div className="space-y-3 p-5">
          <h2 className="font-semibold">{t('orderSummary')}</h2>
          <Separator />

          <SummaryRow
            label={cartText('subtotal')}
            value={formatMoney(quote?.subtotal ?? cart.totals.subtotal, locale)}
          />

          {quote && Number.parseFloat(quote.discount.amount) > 0 ? (
            <SummaryRow label={cartText('discount')} value={`−${formatMoney(quote.discount, locale)}`} />
          ) : null}

          <SummaryRow label={cartText('tax')} value={formatMoney(quote?.tax ?? cart.totals.tax, locale)} />

          <SummaryRow
            label={t('shippingMethod')}
            value={
              quote
                ? Number.parseFloat(quote.shipping.amount) === 0
                  ? t('free')
                  : formatMoney(quote.shipping, locale)
                : '—'
            }
          />

          {quote && Number.parseFloat(quote.codFee.amount) > 0 ? (
            <SummaryRow label={cartText('codFee')} value={formatMoney(quote.codFee, locale)} />
          ) : null}

          <Separator />

          <div className="flex items-baseline justify-between gap-4">
            <span className="font-semibold">{t('orderSummary')}</span>
            <span className="numeric text-lg font-bold">
              {formatMoney(quote?.grandTotal ?? cart.totals.grandTotal, locale)}
            </span>
          </div>

          {error ? (
            <div role="alert" className="rounded-md bg-danger-500/10 p-3 text-sm text-danger-600">
              <p>{error.detail}</p>
              {/*
               * A stock or price conflict is recoverable from the cart, and saying so is more useful
               * than a generic failure — the shopper knows exactly what to do next.
               */}
              {error.is('OUT_OF_STOCK', 'PRICE_CHANGED') ? (
                <Button
                  variant="outline"
                  size="sm"
                  className="mt-2"
                  onClick={() => router.push('/cart')}
                >
                  {cartText('title')}
                </Button>
              ) : null}
            </div>
          ) : null}

          <Button
            size="lg"
            fullWidth
            className="mt-2"
            disabled={!canSubmit}
            loading={placeOrder.isPending}
            onClick={() => placeOrder.mutate()}
          >
            {placeOrder.isPending ? t('placingOrder') : t('placeOrder')}
          </Button>
        </div>
      </Card>
    </div>
  );
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-4 text-sm">
      <span className="text-[var(--muted-foreground)]">{label}</span>
      <span className="numeric font-medium">{value}</span>
    </div>
  );
}
