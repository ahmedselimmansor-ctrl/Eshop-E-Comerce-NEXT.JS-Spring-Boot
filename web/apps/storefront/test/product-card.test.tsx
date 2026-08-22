import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ProductCard as ProductCardData } from '@eshop/api-client';

import { ProductCard } from '../src/components/product/product-card';

/**
 * The product tile, rendered.
 *
 * This is the component a customer sees most often, and the things worth checking are the ones a
 * schema test cannot see: whether a price reaches the DOM at all, whether an out-of-stock product
 * says so, and whether Arabic renders with Western digits like the rest of the storefront.
 */
const labels = {
  outOfStock: 'Out of stock',
  save: (percent: number) => `Save ${percent}%`,
  reviewCount: (count: number) => `${count} reviews`,
};

function product(over: Partial<ProductCardData> = {}): ProductCardData {
  return {
    id: '0192a000-0000-7000-8000-00000000d003',
    slug: 'anker-powercore-20000',
    sku: 'ANK-PB-20K',
    name: 'Anker PowerCore 20000',
    price: { amount: '1899.00', currency: 'EGP' },
    discountPercent: 0,
    ratingAvg: 0,
    ratingCount: 0,
    featured: false,
    ...over,
  } as ProductCardData;
}

describe('ProductCard', () => {
  it('renders the name and a formatted price', () => {
    render(<ProductCard product={product()} locale="en" labels={labels} />);

    expect(screen.getByRole('heading', { name: 'Anker PowerCore 20000' })).toBeInTheDocument();
    expect(screen.getByText(/1,899/)).toBeInTheDocument();
  });

  it('links to the product using its slug', () => {
    render(<ProductCard product={product()} locale="en" labels={labels} />);

    expect(screen.getByRole('link')).toHaveAttribute(
      'href',
      '/products/anker-powercore-20000',
    );
  });

  it('shows a discount badge and the pre-sale price when on sale', () => {
    render(
      <ProductCard
        product={product({
          compareAtPrice: { amount: '2400.00', currency: 'EGP' },
          discountPercent: 21,
        })}
        locale="en"
        labels={labels}
      />,
    );

    expect(screen.getByText('Save 21%')).toBeInTheDocument();
    expect(screen.getByText(/2,400/)).toBeInTheDocument();
  });

  it('does not show a discount badge on a product that is not discounted', () => {
    render(<ProductCard product={product()} locale="en" labels={labels} />);
    expect(screen.queryByText(/Save/)).not.toBeInTheDocument();
  });

  it('marks an out-of-stock product and suppresses the sale badge', () => {
    render(
      <ProductCard
        product={product({ inStock: false, discountPercent: 21 })}
        locale="en"
        labels={labels}
      />,
    );

    expect(screen.getByText('Out of stock')).toBeInTheDocument();
    // A "Save 21%" badge over something nobody can buy is worse than no badge.
    expect(screen.queryByText('Save 21%')).not.toBeInTheDocument();
  });

  it('uses Western digits for prices in Arabic', () => {
    render(<ProductCard product={product()} locale="ar" labels={labels} />);

    const price = screen.getByText(/1[,٬]?899/);
    expect(price).toBeInTheDocument();
    // Eastern Arabic numerals here, Western ones in the cart, reads as a bug rather than as
    // localisation.
    expect(price.textContent).not.toMatch(/[٠-٩]/);
  });

  it('renders the image with the product name as alt text', () => {
    render(
      <ProductCard
        product={product({ imageUrl: 'https://cdn.test/anker.jpg' })}
        locale="en"
        labels={labels}
      />,
    );

    const image = screen.getByRole('img');
    expect(image).toHaveAttribute('src', 'https://cdn.test/anker.jpg');
    expect(image).toHaveAttribute('alt', 'Anker PowerCore 20000');
  });

  it('renders without an image rather than a broken one', () => {
    render(<ProductCard product={product()} locale="en" labels={labels} />);
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('shows the rating only once there are reviews', () => {
    const { unmount } = render(
      <ProductCard product={product()} locale="en" labels={labels} />,
    );
    expect(screen.queryByText(/reviews/)).not.toBeInTheDocument();
    unmount();

    render(
      <ProductCard
        product={product({ ratingAvg: 4.5, ratingCount: 12 })}
        locale="en"
        labels={labels}
      />,
    );
    expect(screen.getByText(/12 reviews/)).toBeInTheDocument();
  });

  it('shows the brand when there is one', () => {
    render(
      <ProductCard product={product({ brandName: 'Anker' })} locale="en" labels={labels} />,
    );
    expect(screen.getByText('Anker')).toBeInTheDocument();
  });
});
