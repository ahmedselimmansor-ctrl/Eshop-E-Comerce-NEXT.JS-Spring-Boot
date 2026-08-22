import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Badge, EmptyState, ErrorState, Field } from '../src/components/ui/primitives';
import { Pagination } from '../src/components/ui/pagination';
import { humanise, OrderStatusBadge } from '../src/components/ui/status';

/**
 * The console's shared UI, rendered.
 *
 * These are the pieces every screen is built from, and the interesting cases are the ones that
 * decide whether an operator can act at all: a disabled control, an error that names the cause, a
 * pagination range that is off by one. None of that is visible to a schema test.
 */
describe('Field', () => {
  it('associates its label with the control', async () => {
    render(
      <Field id="carrier" label="Carrier">
        <input id="carrier" />
      </Field>,
    );

    // getByLabelText only finds it if htmlFor and id actually match, which is the point.
    expect(screen.getByLabelText('Carrier')).toBeInTheDocument();
  });

  it('shows the error instead of the hint when there is one', () => {
    render(
      <Field id="sku" label="SKU" hint="Must be unique" error="Already taken">
        <input id="sku" />
      </Field>,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Already taken');
    // Showing both would let a stale hint sit under a live error.
    expect(screen.queryByText('Must be unique')).not.toBeInTheDocument();
  });

  it('marks a required field for sighted users without announcing a bare asterisk', () => {
    const { container } = render(
      <Field id="sku" label="SKU" required>
        <input id="sku" />
      </Field>,
    );

    const marker = container.querySelector('[aria-hidden]');
    expect(marker).toHaveTextContent('*');
  });
});

describe('Badge', () => {
  it('renders its content', () => {
    render(<Badge tone="danger">Out of stock</Badge>);
    expect(screen.getByText('Out of stock')).toBeInTheDocument();
  });
});

describe('status badges', () => {
  it('humanises a screaming-snake status', () => {
    expect(humanise('PENDING_PAYMENT')).toBe('Pending payment');
    expect(humanise('PAID')).toBe('Paid');
  });

  it('renders every order status the API can send', () => {
    for (const status of [
      'PENDING_PAYMENT', 'PAID', 'PROCESSING', 'SHIPPED',
      'DELIVERED', 'CANCELLED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'FAILED',
    ] as const) {
      const { unmount } = render(<OrderStatusBadge status={status} />);
      expect(screen.getByText(humanise(status))).toBeInTheDocument();
      unmount();
    }
  });
});

describe('EmptyState', () => {
  it('renders a title, a hint and an action', async () => {
    const onClick = vi.fn();
    render(
      <EmptyState
        title="No products match these filters"
        hint="Try a different search term."
        action={<button onClick={onClick}>Clear filters</button>}
      />,
    );

    expect(screen.getByText('No products match these filters')).toBeInTheDocument();
    expect(screen.getByText('Try a different search term.')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(onClick).toHaveBeenCalledOnce();
  });
});

describe('ErrorState', () => {
  it('offers a retry that calls back', async () => {
    const onRetry = vi.fn();
    render(<ErrorState onRetry={onRetry} />);

    await userEvent.click(screen.getByRole('button'));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('shows the message it was given rather than a generic one', () => {
    render(<ErrorState message="Could not reach the search cluster." />);
    expect(screen.getByText('Could not reach the search cluster.')).toBeInTheDocument();
  });
});

describe('Pagination', () => {
  const page = (over: Partial<Parameters<typeof Pagination>[0]['page']> = {}) => ({
    number: 0,
    size: 20,
    totalElements: 41,
    totalPages: 3,
    first: true,
    last: false,
    ...over,
  });

  it('renders nothing when there is nothing to page through', () => {
    const { container } = render(
      <Pagination page={page({ totalElements: 0 })} onPageChange={vi.fn()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('shows a one-based range on the first page', () => {
    render(<Pagination page={page()} onPageChange={vi.fn()} label="products" />);
    // Zero-based page numbers, one-based display: the off-by-one people actually notice.
    expect(screen.getByText(/1.*20 of 41 products/)).toBeInTheDocument();
  });

  it('clamps the range on the last page to the real total', () => {
    render(
      <Pagination
        page={page({ number: 2, first: false, last: true })}
        onPageChange={vi.fn()}
      />,
    );
    expect(screen.getByText(/41.*41 of 41/)).toBeInTheDocument();
  });

  it('disables Previous on the first page and Next on the last', () => {
    const { unmount } = render(<Pagination page={page()} onPageChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /next/i })).toBeEnabled();
    unmount();

    render(
      <Pagination page={page({ number: 2, first: false, last: true })} onPageChange={vi.fn()} />,
    );
    expect(screen.getByRole('button', { name: /previous/i })).toBeEnabled();
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled();
  });

  it('moves by one page in the direction clicked', async () => {
    const onPageChange = vi.fn();
    render(
      <Pagination
        page={page({ number: 1, first: false })}
        onPageChange={onPageChange}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: /next/i }));
    expect(onPageChange).toHaveBeenCalledWith(2);

    await userEvent.click(screen.getByRole('button', { name: /previous/i }));
    expect(onPageChange).toHaveBeenCalledWith(0);
  });
});
