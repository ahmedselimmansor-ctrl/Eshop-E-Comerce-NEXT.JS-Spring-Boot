'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Ban, CreditCard, RotateCcw, StickyNote, Truck } from 'lucide-react';
import Link from 'next/link';
import { use, useState, type ReactNode } from 'react';
import { ApiError, newIdempotencyKey } from '@eshop/api-client';
import { adminApi } from '@/lib/api';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  ErrorState,
  Field,
  Input,
  Select,
  Skeleton,
  Table,
  Td,
  Textarea,
  Th,
} from '@/components/ui/primitives';
import { OrderStatusBadge, PaymentStatusBadge, humanise } from '@/components/ui/status';
import { hasPermission } from '@/stores/auth-store';
import { formatDateTime, formatMoney } from '@/lib/utils';

/**
 * One order, with everything staff need to act on it.
 *
 * The actions are the point — fulfil, refund, change status, cancel, annotate. Each is gated on the
 * permission its endpoint enforces, and each invalidates the queries whose data it just changed so
 * the list and the dashboard counters do not go stale behind the operator's back.
 */
export default function OrderDetailPage({ params }: { params: Promise<{ orderId: string }> }) {
  const { orderId } = use(params);
  const queryClient = useQueryClient();

  const detail = useQuery({
    queryKey: ['admin', 'order', orderId],
    queryFn: () => adminApi().orders.get(orderId),
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['admin', 'order', orderId] });
    void queryClient.invalidateQueries({ queryKey: ['admin', 'orders'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  }

  if (detail.isLoading) return <Skeleton className="h-96 w-full" />;
  if (detail.isError || !detail.data) {
    return <ErrorState onRetry={() => void detail.refetch()} />;
  }

  const { order, adminNote, ip, userAgent, payments, returns } = detail.data;
  const address = order.shippingAddress as Record<string, string | undefined>;

  const outstanding = order.items.reduce(
    (total, item) => total + (item.quantity - item.fulfilledQuantity),
    0,
  );

  return (
    <div className="mx-auto max-w-6xl space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="ghost" size="sm" asChild>
          <Link href="/orders">
            <ArrowLeft aria-hidden />
            Orders
          </Link>
        </Button>

        <h1 className="numeric text-xl font-semibold">{order.orderNumber}</h1>
        <OrderStatusBadge status={order.status} />
        <PaymentStatusBadge status={order.paymentStatus} />

        <span className="ms-auto text-xs text-[var(--muted-foreground)]">
          Placed {formatDateTime(order.placedAt)}
        </span>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <Card>
            <CardHeader>
              <CardTitle>Items</CardTitle>
            </CardHeader>

            <Table>
              <thead>
                <tr>
                  <Th>Product</Th>
                  <Th className="text-end">Unit</Th>
                  <Th className="text-end">Qty</Th>
                  <Th className="text-end">Fulfilled</Th>
                  <Th className="text-end">Total</Th>
                </tr>
              </thead>
              <tbody>
                {order.items.map((item) => (
                  <tr key={item.id}>
                    <Td>
                      <p className="font-medium">{item.name}</p>
                      <p className="numeric text-xs text-[var(--muted-foreground)]">
                        {item.sku}
                        {item.variantLabel ? ` · ${item.variantLabel}` : ''}
                      </p>
                    </Td>
                    <Td className="numeric text-end">{formatMoney(item.unitPrice)}</Td>
                    <Td className="numeric text-end">{item.quantity}</Td>
                    <Td className="numeric text-end">
                      {item.fulfilledQuantity}
                      {item.refundedQuantity > 0 ? (
                        <span className="ms-1 text-xs text-[var(--muted-foreground)]">
                          ({item.refundedQuantity} refunded)
                        </span>
                      ) : null}
                    </Td>
                    <Td className="numeric text-end font-medium">{formatMoney(item.lineTotal)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>

            <CardBody className="space-y-1.5 text-sm">
              <Row label="Subtotal" value={formatMoney(order.totals.subtotal)} />
              {Number(order.totals.discount.amount) > 0 ? (
                <Row
                  label={`Discount${order.couponCode ? ` (${order.couponCode})` : ''}`}
                  value={`−${formatMoney(order.totals.discount)}`}
                />
              ) : null}
              <Row label="Tax" value={formatMoney(order.totals.tax)} />
              <Row label="Shipping" value={formatMoney(order.totals.shipping)} />
              {Number(order.totals.codFee.amount) > 0 ? (
                <Row label="Cash-on-delivery fee" value={formatMoney(order.totals.codFee)} />
              ) : null}
              <div className="flex items-baseline justify-between border-t border-[var(--border)] pt-2 font-semibold">
                <span>Total</span>
                <span className="numeric">{formatMoney(order.totals.grandTotal)}</span>
              </div>
            </CardBody>
          </Card>

          {/* Failed attempts are shown, not hidden — they are the first thing anyone investigating
              a "my card was declined" ticket needs to see. */}
          <Card>
            <CardHeader>
              <CardTitle>Payments</CardTitle>
            </CardHeader>

            {payments.length === 0 ? (
              <CardBody className="text-sm text-[var(--muted-foreground)]">
                No payment attempts recorded.
              </CardBody>
            ) : (
              <Table>
                <thead>
                  <tr>
                    <Th>Provider</Th>
                    <Th>Status</Th>
                    <Th className="text-end">Amount</Th>
                    <Th>When</Th>
                  </tr>
                </thead>
                <tbody>
                  {payments.map((attempt) => (
                    <tr key={attempt.id}>
                      <Td>
                        <span className="font-medium">{attempt.provider}</span>
                        {attempt.cardLast4 ? (
                          <span className="numeric ms-1 text-xs text-[var(--muted-foreground)]">
                            {attempt.cardBrand} ••{attempt.cardLast4}
                          </span>
                        ) : null}
                        {attempt.failureReason ? (
                          <p className="text-xs text-danger-500">{attempt.failureReason}</p>
                        ) : null}
                      </Td>
                      <Td>
                        <Badge
                          tone={
                            attempt.status === 'CAPTURED'
                              ? 'success'
                              : attempt.status === 'FAILED'
                                ? 'danger'
                                : 'neutral'
                          }
                        >
                          {humanise(attempt.status)}
                        </Badge>
                      </Td>
                      <Td className="numeric text-end">{formatMoney(attempt.amount)}</Td>
                      <Td className="whitespace-nowrap text-xs text-[var(--muted-foreground)]">
                        {formatDateTime(attempt.createdAt)}
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            )}
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Timeline</CardTitle>
            </CardHeader>
            <CardBody>
              <ol className="space-y-3">
                {order.timeline.map((entry, index) => (
                  <li key={`${entry.status}-${index}`} className="flex gap-3 text-sm">
                    <span className="mt-1.5 size-2 shrink-0 rounded-full bg-brand-600" aria-hidden />
                    <div>
                      <p className="font-medium">{humanise(entry.status)}</p>
                      {entry.note ? (
                        <p className="text-xs text-[var(--muted-foreground)]">{entry.note}</p>
                      ) : null}
                      <p className="text-xs text-[var(--muted-foreground)]">
                        {formatDateTime(entry.at)}
                      </p>
                    </div>
                  </li>
                ))}
              </ol>
            </CardBody>
          </Card>
        </div>

        <div className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle>Shipping</CardTitle>
            </CardHeader>
            <CardBody className="space-y-1 text-sm">
              <p className="font-medium">{address.recipientName}</p>
              <p className="text-[var(--muted-foreground)]">{address.formatted}</p>
              <p className="numeric text-[var(--muted-foreground)]">{address.phone}</p>
              {order.shippingMethodName ? (
                <p className="pt-1">
                  <span className="text-[var(--muted-foreground)]">Method: </span>
                  {order.shippingMethodName}
                </p>
              ) : null}
            </CardBody>
          </Card>

          {returns.length > 0 ? (
            <Card>
              <CardHeader>
                <CardTitle>Returns</CardTitle>
              </CardHeader>
              <CardBody className="space-y-2 text-sm">
                {returns.map((entry) => (
                  <div key={entry.id} className="flex items-center justify-between gap-2">
                    <span className="numeric">{entry.returnNumber}</span>
                    <Badge>{humanise(entry.status)}</Badge>
                  </div>
                ))}
              </CardBody>
            </Card>
          ) : null}

          <OrderActions
            orderId={orderId}
            currentStatus={order.status}
            grandTotal={Number(order.totals.grandTotal.amount)}
            currency={order.totals.grandTotal.currency}
            outstandingItems={outstanding}
            items={order.items.map((item) => ({
              id: item.id,
              name: item.name,
              outstanding: item.quantity - item.fulfilledQuantity,
            }))}
            adminNote={adminNote ?? ''}
            onDone={refresh}
          />

          {/* Last, deliberately: useful for a fraud check, noise the rest of the time. */}
          <Card>
            <CardHeader>
              <CardTitle>Origin</CardTitle>
            </CardHeader>
            <CardBody className="space-y-1 text-xs text-[var(--muted-foreground)]">
              <p className="numeric">{ip ?? '—'}</p>
              <p className="line-clamp-3 break-all">{userAgent ?? '—'}</p>
            </CardBody>
          </Card>
        </div>
      </div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <span className="text-[var(--muted-foreground)]">{label}</span>
      <span className="numeric">{value}</span>
    </div>
  );
}

// --------------------------------------------------------------------- actions --

type Panel = 'fulfil' | 'status' | 'refund' | 'cancel' | 'note' | null;

function OrderActions({
  orderId,
  currentStatus,
  grandTotal,
  currency,
  outstandingItems,
  items,
  adminNote,
  onDone,
}: {
  orderId: string;
  currentStatus: string;
  grandTotal: number;
  currency: string;
  outstandingItems: number;
  items: { id: string; name: string; outstanding: number }[];
  adminNote: string;
  onDone: () => void;
}) {
  const [panel, setPanel] = useState<Panel>(null);

  const canFulfil = hasPermission('order:fulfil');
  const canRefund = hasPermission('order:refund');
  const canWrite = hasPermission('order:write');

  const [carrier, setCarrier] = useState('');
  const [tracking, setTracking] = useState('');
  const [quantities, setQuantities] = useState<Record<string, number>>(() =>
    Object.fromEntries(
      items.filter((item) => item.outstanding > 0).map((item) => [item.id, item.outstanding]),
    ),
  );

  const [status, setStatus] = useState(currentStatus);
  const [note, setNote] = useState('');
  const [internalNote, setInternalNote] = useState(adminNote);

  const [refundAmount, setRefundAmount] = useState(String(grandTotal));
  const [refundReason, setRefundReason] = useState('');

  const close = () => setPanel(null);
  const settle = () => {
    onDone();
    close();
  };

  const fulfil = useMutation({
    mutationFn: () =>
      adminApi().orders.fulfil(orderId, {
        quantitiesByItemId: Object.fromEntries(
          Object.entries(quantities).filter(([, quantity]) => quantity > 0),
        ),
        carrier: carrier.trim(),
        trackingNumber: tracking.trim() || undefined,
      }),
    onSuccess: settle,
  });

  const changeStatus = useMutation({
    mutationFn: () => adminApi().orders.setStatus(orderId, status, note.trim() || undefined),
    onSuccess: settle,
  });

  const cancel = useMutation({
    mutationFn: () => adminApi().orders.cancel(orderId, note.trim() || undefined),
    onSuccess: settle,
  });

  const refund = useMutation({
    /*
     * A fresh idempotency key per submission.
     *
     * If the response is lost the operator will click again, and without the key that second click
     * is a second refund. With it, the server recognises the retry and returns the original result.
     */
    mutationFn: () =>
      adminApi().orders.refund(
        orderId,
        { amount: Number(refundAmount), reason: refundReason.trim() },
        newIdempotencyKey(),
      ),
    onSuccess: settle,
  });

  const saveNote = useMutation({
    mutationFn: () => adminApi().orders.addNote(orderId, internalNote.trim()),
    onSuccess: settle,
  });

  const pending =
    fulfil.isPending ||
    changeStatus.isPending ||
    cancel.isPending ||
    refund.isPending ||
    saveNote.isPending;

  const error = [fulfil.error, changeStatus.error, cancel.error, refund.error, saveNote.error].find(
    Boolean,
  );

  return (
    <Card>
      <CardHeader>
        <CardTitle>Actions</CardTitle>
      </CardHeader>

      <CardBody className="space-y-2">
        {canFulfil && outstandingItems > 0 ? (
          <Button
            variant="outline"
            className="w-full justify-start"
            onClick={() => setPanel('fulfil')}
          >
            <Truck aria-hidden />
            Fulfil {outstandingItems} item{outstandingItems === 1 ? '' : 's'}
          </Button>
        ) : null}

        {canWrite ? (
          <Button
            variant="outline"
            className="w-full justify-start"
            onClick={() => setPanel('status')}
          >
            <RotateCcw aria-hidden />
            Change status
          </Button>
        ) : null}

        {canRefund ? (
          <Button
            variant="outline"
            className="w-full justify-start"
            onClick={() => setPanel('refund')}
          >
            <CreditCard aria-hidden />
            Refund
          </Button>
        ) : null}

        {canWrite ? (
          <>
            <Button
              variant="outline"
              className="w-full justify-start"
              onClick={() => setPanel('note')}
            >
              <StickyNote aria-hidden />
              Internal note
            </Button>

            <Button
              variant="ghost"
              className="w-full justify-start text-danger-500"
              onClick={() => setPanel('cancel')}
            >
              <Ban aria-hidden />
              Cancel order
            </Button>
          </>
        ) : null}

        {!canWrite && !canFulfil && !canRefund ? (
          <p className="text-xs text-[var(--muted-foreground)]">
            Your role can view this order but not act on it.
          </p>
        ) : null}

        {panel === 'fulfil' ? (
          <ActionPanel
            title="Fulfil"
            onCancel={close}
            onConfirm={() => fulfil.mutate()}
            pending={pending}
            disabled={!carrier.trim()}
          >
            {items
              .filter((item) => item.outstanding > 0)
              .map((item) => (
                <Field key={item.id} id={`qty-${item.id}`} label={item.name}>
                  <Input
                    id={`qty-${item.id}`}
                    type="number"
                    min={0}
                    max={item.outstanding}
                    value={quantities[item.id] ?? 0}
                    onChange={(event) =>
                      setQuantities((current) => ({
                        ...current,
                        // Clamped to what is actually outstanding, so the request cannot fail on a
                        // limit the form already knows about.
                        [item.id]: Math.min(
                          item.outstanding,
                          Math.max(0, Number(event.target.value)),
                        ),
                      }))
                    }
                  />
                </Field>
              ))}

            <Field id="carrier" label="Carrier" required>
              <Input id="carrier" value={carrier} onChange={(e) => setCarrier(e.target.value)} />
            </Field>
            <Field id="tracking" label="Tracking number">
              <Input id="tracking" value={tracking} onChange={(e) => setTracking(e.target.value)} />
            </Field>
          </ActionPanel>
        ) : null}

        {panel === 'status' ? (
          <ActionPanel
            title="Change status"
            onCancel={close}
            onConfirm={() => changeStatus.mutate()}
            pending={pending}
          >
            <Field id="status" label="New status" required>
              <Select id="status" value={status} onChange={(e) => setStatus(e.target.value)}>
                {['PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED'].map((value) => (
                  <option key={value} value={value}>
                    {humanise(value)}
                  </option>
                ))}
              </Select>
            </Field>
            <Field id="status-note" label="Note" hint="Recorded on the order timeline.">
              <Textarea id="status-note" value={note} onChange={(e) => setNote(e.target.value)} />
            </Field>
          </ActionPanel>
        ) : null}

        {panel === 'refund' ? (
          <ActionPanel
            title="Refund"
            onCancel={close}
            onConfirm={() => refund.mutate()}
            pending={pending}
            disabled={!refundReason.trim() || !(Number(refundAmount) > 0)}
            destructive
          >
            <Field
              id="refund-amount"
              label={`Amount (${currency})`}
              hint={`Order total is ${formatMoney({ amount: String(grandTotal), currency })}.`}
              required
            >
              <Input
                id="refund-amount"
                type="number"
                step="0.01"
                min="0.01"
                max={grandTotal}
                value={refundAmount}
                onChange={(e) => setRefundAmount(e.target.value)}
              />
            </Field>
            <Field id="refund-reason" label="Reason" required>
              <Input
                id="refund-reason"
                value={refundReason}
                onChange={(e) => setRefundReason(e.target.value)}
              />
            </Field>
            <p className="text-xs text-[var(--muted-foreground)]">
              This returns money to the customer through the original provider and cannot be undone
              from here.
            </p>
          </ActionPanel>
        ) : null}

        {panel === 'cancel' ? (
          <ActionPanel
            title="Cancel order"
            onCancel={close}
            onConfirm={() => cancel.mutate()}
            pending={pending}
            destructive
          >
            <Field id="cancel-reason" label="Reason">
              <Textarea id="cancel-reason" value={note} onChange={(e) => setNote(e.target.value)} />
            </Field>
            <p className="text-xs text-[var(--muted-foreground)]">
              Reserved stock is released and any coupon use is returned to the customer.
            </p>
          </ActionPanel>
        ) : null}

        {panel === 'note' ? (
          <ActionPanel
            title="Internal note"
            onCancel={close}
            onConfirm={() => saveNote.mutate()}
            pending={pending}
          >
            <Field id="admin-note" label="Note" hint="Staff only — never shown to the customer.">
              <Textarea
                id="admin-note"
                value={internalNote}
                onChange={(e) => setInternalNote(e.target.value)}
              />
            </Field>
          </ActionPanel>
        ) : null}

        {error ? (
          <p role="alert" className="text-xs text-danger-500">
            {error instanceof ApiError ? error.detail : 'Something went wrong.'}
          </p>
        ) : null}

        {adminNote && panel !== 'note' ? (
          <p className="rounded-md bg-[var(--muted)] p-2 text-xs">
            <span className="font-medium">Note: </span>
            {adminNote}
          </p>
        ) : null}
      </CardBody>
    </Card>
  );
}

function ActionPanel({
  title,
  children,
  onCancel,
  onConfirm,
  pending,
  disabled,
  destructive,
}: {
  title: string;
  children: ReactNode;
  onCancel: () => void;
  onConfirm: () => void;
  pending: boolean;
  disabled?: boolean;
  destructive?: boolean;
}) {
  return (
    <div className="space-y-3 rounded-md border border-[var(--border)] p-3">
      <p className="text-xs font-semibold">{title}</p>
      {children}
      <div className="flex gap-2">
        <Button variant="ghost" size="sm" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          size="sm"
          variant={destructive ? 'danger' : 'primary'}
          loading={pending}
          disabled={disabled}
          onClick={onConfirm}
        >
          Confirm
        </Button>
      </div>
    </div>
  );
}
