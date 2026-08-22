'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, MessageSquareReply, Star, X } from 'lucide-react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useState } from 'react';
import { ApiError, type ReviewModerationStatus } from '@eshop/api-client';
import { adminApi } from '@/lib/api';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorState,
  Field,
  Input,
  Select,
  Skeleton,
  Textarea,
} from '@/components/ui/primitives';
import { Pagination } from '@/components/ui/pagination';
import { ReviewStatusBadge, humanise } from '@/components/ui/status';
import { formatDateTime } from '@/lib/utils';

const STATUSES: ReviewModerationStatus[] = ['PENDING', 'APPROVED', 'REJECTED'];

export default function ReviewsPage() {
  return (
    <Suspense fallback={<Skeleton className="h-96 w-full" />}>
      <ReviewQueue />
    </Suspense>
  );
}

/**
 * Review moderation.
 *
 * A queue, not a table. Each review needs to be *read* before it is approved, so the layout gives
 * the text room rather than truncating it into a cell — a moderator who cannot see the review
 * without clicking will approve without reading.
 */
function ReviewQueue() {
  const router = useRouter();
  const params = useSearchParams();
  const queryClient = useQueryClient();

  // Defaults to the only status that needs work.
  const status = (params.get('status') as ReviewModerationStatus | null) ?? 'PENDING';
  const page = Number(params.get('page') ?? 0);

  const [replyingTo, setReplyingTo] = useState<string | null>(null);
  const [rejectingId, setRejectingId] = useState<string | null>(null);
  const [text, setText] = useState('');

  const reviews = useQuery({
    queryKey: ['admin', 'reviews', { status, page }],
    queryFn: () => adminApi().reviews.list({ status, page, size: 20 }),
    placeholderData: keepPreviousData,
  });

  function done() {
    setReplyingTo(null);
    setRejectingId(null);
    setText('');
    void queryClient.invalidateQueries({ queryKey: ['admin', 'reviews'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  }

  const approve = useMutation({
    mutationFn: (reviewId: string) => adminApi().reviews.approve(reviewId),
    onSuccess: done,
  });

  const reject = useMutation({
    mutationFn: (reviewId: string) => adminApi().reviews.reject(reviewId, text.trim()),
    onSuccess: done,
  });

  const reply = useMutation({
    mutationFn: (reviewId: string) => adminApi().reviews.reply(reviewId, text.trim()),
    onSuccess: done,
  });

  function setParam(changes: Record<string, string | undefined>) {
    const next = new URLSearchParams(params.toString());
    for (const [key, value] of Object.entries(changes)) {
      if (!value) next.delete(key);
      else next.set(key, value);
    }
    if (!('page' in changes)) next.delete('page');
    router.replace(`/reviews?${next.toString()}`);
  }

  const rows = reviews.data?.content ?? [];
  const error = approve.error ?? reject.error ?? reply.error;

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold">Reviews</h1>

        <Select
          className="w-40"
          value={status}
          onChange={(event) => setParam({ status: event.target.value })}
          aria-label="Moderation status"
        >
          {STATUSES.map((value) => (
            <option key={value} value={value}>
              {humanise(value)}
            </option>
          ))}
        </Select>
      </div>

      {error ? (
        <p role="alert" className="text-xs text-danger-500">
          {error instanceof ApiError ? error.detail : 'Something went wrong.'}
        </p>
      ) : null}

      {reviews.isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 4 }, (_, index) => (
            <Skeleton key={index} className="h-32 w-full" />
          ))}
        </div>
      ) : reviews.isError ? (
        <Card>
          <ErrorState onRetry={() => void reviews.refetch()} />
        </Card>
      ) : rows.length === 0 ? (
        <Card>
          <EmptyState
            title={status === 'PENDING' ? 'Nothing waiting for moderation' : 'No reviews here'}
            hint={status === 'PENDING' ? 'The queue is clear.' : undefined}
          />
        </Card>
      ) : (
        <div className="space-y-3">
          {rows.map((review) => (
            <Card key={review.id} className="p-4">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <div className="flex items-center gap-1.5">
                    <span className="flex" aria-label={`${review.rating} out of 5`}>
                      {Array.from({ length: 5 }, (_, index) => (
                        <Star
                          key={index}
                          className={
                            index < review.rating
                              ? 'size-3.5 fill-warning-500 text-warning-500'
                              : 'size-3.5 text-[var(--border)]'
                          }
                          aria-hidden
                        />
                      ))}
                    </span>

                    {review.title ? <span className="font-medium">{review.title}</span> : null}

                    {review.verifiedPurchase ? (
                      <Badge tone="success">Verified purchase</Badge>
                    ) : null}
                  </div>

                  <p className="mt-0.5 text-xs text-[var(--muted-foreground)]">
                    {review.authorName} · {formatDateTime(review.createdAt)}
                  </p>
                </div>

                <ReviewStatusBadge status={review.status} />
              </div>

              {review.body ? (
                <p className="mt-3 whitespace-pre-wrap text-sm leading-relaxed">{review.body}</p>
              ) : null}

              {review.mediaUrls.length > 0 ? (
                <div className="mt-3 flex flex-wrap gap-2">
                  {review.mediaUrls.map((url) => (
                    // eslint-disable-next-line @next/next/no-img-element -- customer uploads live on
                    // an arbitrary CDN host, so the optimiser's allowlist cannot cover them.
                    <img
                      key={url}
                      src={url}
                      alt=""
                      className="size-16 rounded object-cover"
                      loading="lazy"
                    />
                  ))}
                </div>
              ) : null}

              {review.adminReply ? (
                <div className="mt-3 rounded-md bg-[var(--muted)] p-3 text-sm">
                  <p className="text-xs font-medium">Store reply</p>
                  <p className="mt-0.5 whitespace-pre-wrap">{review.adminReply}</p>
                </div>
              ) : null}

              {/* ---- actions ---- */}
              {rejectingId === review.id || replyingTo === review.id ? (
                <div className="mt-3 space-y-2">
                  <Field
                    id={`text-${review.id}`}
                    label={rejectingId === review.id ? 'Rejection reason' : 'Public reply'}
                    hint={
                      rejectingId === review.id
                        ? 'Kept internally; the customer sees only that it was not published.'
                        : 'Shown under the review on the storefront.'
                    }
                    required
                  >
                    {rejectingId === review.id ? (
                      <Input
                        id={`text-${review.id}`}
                        value={text}
                        onChange={(event) => setText(event.target.value)}
                      />
                    ) : (
                      <Textarea
                        id={`text-${review.id}`}
                        value={text}
                        onChange={(event) => setText(event.target.value)}
                      />
                    )}
                  </Field>

                  <div className="flex gap-2">
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        setRejectingId(null);
                        setReplyingTo(null);
                        setText('');
                      }}
                    >
                      Cancel
                    </Button>
                    <Button
                      size="sm"
                      variant={rejectingId === review.id ? 'danger' : 'primary'}
                      disabled={!text.trim()}
                      loading={reject.isPending || reply.isPending}
                      onClick={() =>
                        rejectingId === review.id
                          ? reject.mutate(review.id)
                          : reply.mutate(review.id)
                      }
                    >
                      Confirm
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="mt-3 flex flex-wrap gap-2">
                  {review.status !== 'APPROVED' ? (
                    <Button
                      size="sm"
                      loading={approve.isPending && approve.variables === review.id}
                      onClick={() => approve.mutate(review.id)}
                    >
                      <Check aria-hidden />
                      Approve
                    </Button>
                  ) : null}

                  {review.status !== 'REJECTED' ? (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => {
                        setRejectingId(review.id);
                        setText('');
                      }}
                    >
                      <X aria-hidden />
                      Reject
                    </Button>
                  ) : null}

                  {review.status === 'APPROVED' ? (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => {
                        setReplyingTo(review.id);
                        setText(review.adminReply ?? '');
                      }}
                    >
                      <MessageSquareReply aria-hidden />
                      {review.adminReply ? 'Edit reply' : 'Reply'}
                    </Button>
                  ) : null}
                </div>
              )}
            </Card>
          ))}

          {reviews.data ? (
            <Card>
              <Pagination
                page={reviews.data.page}
                label="reviews"
                onPageChange={(next) => setParam({ page: String(next) })}
              />
            </Card>
          ) : null}
        </div>
      )}
    </div>
  );
}
