'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { Check, Minus, Plus, ShoppingBag } from 'lucide-react';
import { useMemo, useState } from 'react';
import { ApiError, formatMoney, type ProductDetail } from '@eshop/api-client';
import { Badge } from '@/components/ui/primitives';
import { Button } from '@/components/ui/button';
import { WishlistToggle } from '@/components/product/wishlist-toggle';
import { browserApi } from '@/lib/api-browser';
import { cn } from '@/lib/utils';

/**
 * Variant selection, quantity, and add-to-cart.
 *
 * The one interactive island on the product page. Selection is held as a map of
 * `attributeCode → valueCode` and resolved to a variant by matching against each variant's own
 * `selection` map - the server already told us which combination each SKU represents, so the
 * client never has to reconstruct that from the axes.
 */
export function ProductPurchasePanel({
  product,
  locale,
}: {
  product: ProductDetail;
  locale: string;
}) {
  // `selectOption`, `onlyLeft` and `save` all take an argument, so they cannot be handed down from
  // the server component that renders this island - it reads its own messages instead.
  const t = useTranslations('product');

  const queryClient = useQueryClient();

  // Pre-selects the first in-stock variant so a single-variant product needs no interaction and a
  // multi-variant one opens on something buyable rather than on an empty state.
  const [selection, setSelection] = useState<Record<string, string>>(() => {
    const firstAvailable = product.variants.find((variant) => variant.inStock && variant.active);
    return firstAvailable?.selection ?? {};
  });

  const [quantity, setQuantity] = useState(1);
  const [justAdded, setJustAdded] = useState(false);

  /** The variant whose axis values match the current selection exactly. */
  const selectedVariant = useMemo(() => {
    if (product.variants.length === 0) return null;

    return (
      product.variants.find((variant) =>
        Object.entries(variant.selection).every(([code, value]) => selection[code] === value),
      ) ?? null
    );
  }, [product.variants, selection]);

  const isVariable = product.type === 'VARIABLE';
  const selectionComplete = !isVariable || selectedVariant !== null;

  const available = selectedVariant
    ? (selectedVariant.availableQuantity ?? 0)
    : (product.availableQuantity ?? 0);

  const purchasable = selectionComplete && available > 0;

  const price = selectedVariant?.price ?? product.price;
  const compareAtPrice = selectedVariant?.compareAtPrice ?? product.compareAtPrice;
  const discountPercent = selectedVariant?.discountPercent ?? product.discountPercent;

  const addToCart = useMutation({
    mutationFn: () =>
      browserApi().cart.addItem(product.id, quantity, selectedVariant?.id),

    onSuccess: (cart) => {
      // Seeds the cache directly with the response rather than invalidating, so the header badge
      // updates in the same frame instead of after a second round trip.
      queryClient.setQueryData(['cart'], cart);
      setJustAdded(true);
      setTimeout(() => setJustAdded(false), 2200);
    },
  });

  /**
   * Whether a value on one axis leads to any buyable variant, given the other selections.
   *
   * Used to disable dead options: offering "Red" when no red variant exists in the chosen size
   * sends the shopper down a path that ends in "out of stock".
   */
  function isValueAvailable(axisCode: string, valueCode: string): boolean {
    const hypothetical = { ...selection, [axisCode]: valueCode };

    return product.variants.some(
      (variant) =>
        variant.active &&
        variant.inStock &&
        Object.entries(variant.selection).every(
          ([code, value]) => hypothetical[code] === undefined || hypothetical[code] === value,
        ) &&
        variant.selection[axisCode] === valueCode,
    );
  }

  const errorMessage =
    addToCart.error instanceof ApiError ? addToCart.error.detail : null;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-baseline gap-3">
        <span className="numeric text-2xl font-bold">{formatMoney(price, locale)}</span>

        {compareAtPrice ? (
          <>
            <span className="numeric text-sm text-[var(--muted-foreground)] line-through">
              {formatMoney(compareAtPrice, locale)}
            </span>
            {discountPercent > 0 ? <Badge tone="sale">{t('save', { percent: discountPercent })}</Badge> : null}
          </>
        ) : null}
      </div>

      {/* Variant axes */}
      {product.variantAxes.map((axis) => (
        <fieldset key={axis.code}>
          <legend className="mb-2 text-sm font-medium">{t('selectOption', { option: axis.name })}</legend>

          <div className="flex flex-wrap gap-2">
            {axis.values.map((value) => {
              const selected = selection[axis.code] === value.code;
              const enabled = isValueAvailable(axis.code, value.code);
              const isColour = axis.type === 'COLOR' && value.hexColor;

              return (
                <button
                  key={value.id}
                  type="button"
                  disabled={!enabled}
                  onClick={() => setSelection((current) => ({ ...current, [axis.code]: value.code }))}
                  aria-pressed={selected}
                  title={value.value}
                  className={cn(
                    'rounded-[var(--radius-control)] border-2 text-sm transition-colors',
                    isColour ? 'size-9 p-0.5' : 'min-w-12 px-3 py-2',
                    selected ? 'border-brand-600' : 'border-[var(--border)] hover:border-brand-300',
                    // Struck through rather than merely greyed out, so the reason is legible at a
                    // glance: this combination does not exist.
                    !enabled && 'cursor-not-allowed opacity-40 line-through',
                  )}
                >
                  {isColour ? (
                    <>
                      <span
                        className="block size-full rounded-[calc(var(--radius-control)-2px)]"
                        style={{ backgroundColor: value.hexColor ?? undefined }}
                        aria-hidden
                      />
                      <span className="sr-only">{value.value}</span>
                    </>
                  ) : (
                    value.value
                  )}
                </button>
              );
            })}
          </div>
        </fieldset>
      ))}

      {/* Stock signal */}
      {purchasable && available <= 5 ? (
        <p className="text-sm font-medium text-sale-600">{t('onlyLeft', { count: available })}</p>
      ) : null}

      {!purchasable && selectionComplete ? (
        <Badge tone="danger">{t('outOfStock')}</Badge>
      ) : null}

      {/* Quantity + add to cart */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex items-center rounded-[var(--radius-control)] border border-[var(--border)]">
          <button
            type="button"
            onClick={() => setQuantity((value) => Math.max(1, value - 1))}
            disabled={quantity <= 1}
            className="flex size-11 items-center justify-center disabled:opacity-40"
            aria-label="−"
          >
            <Minus className="size-4" aria-hidden />
          </button>

          <span className="numeric w-12 text-center text-sm font-medium" aria-live="polite">
            {quantity}
          </span>

          <button
            type="button"
            // Capped at what is actually available, so the request cannot fail on a limit the UI
            // already knows about.
            onClick={() => setQuantity((value) => Math.min(available || 1, value + 1))}
            disabled={quantity >= available}
            className="flex size-11 items-center justify-center disabled:opacity-40"
            aria-label="+"
          >
            <Plus className="size-4" aria-hidden />
          </button>
        </div>

        <Button
          size="lg"
          className="flex-1"
          disabled={!purchasable}
          loading={addToCart.isPending}
          onClick={() => addToCart.mutate()}
        >
          {justAdded ? (
            <>
              <Check aria-hidden />
              {t('addedToCart')}
            </>
          ) : (
            <>
              <ShoppingBag aria-hidden />
              {addToCart.isPending ? t('adding') : t('addToCart')}
            </>
          )}
        </Button>

        {/* Saving is offered even when the item is not purchasable - "tell me when it's back" is
            precisely what a wishlist is for. */}
        <WishlistToggle productId={product.id} variantId={selectedVariant?.id ?? null} />
      </div>

      {errorMessage ? (
        <p role="alert" className="text-sm text-danger-500">
          {errorMessage}
        </p>
      ) : null}

      <p className="numeric text-xs text-[var(--muted-foreground)]">
        {t('sku')}: {selectedVariant?.sku ?? product.sku}
      </p>
    </div>
  );
}
