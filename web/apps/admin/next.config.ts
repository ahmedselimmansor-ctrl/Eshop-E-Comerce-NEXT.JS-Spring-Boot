import type { NextConfig } from 'next';

/**
 * Admin console.
 *
 * Deliberately simpler than the storefront: no i18n routing (staff work in one language), no image
 * optimisation for a public CDN, and no SEO metadata — the whole surface is `noindex`.
 */
const config: NextConfig = {
  reactStrictMode: true,
  output: 'standalone',
  transpilePackages: ['@eshop/api-client'],
  poweredByHeader: false,

  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'X-Frame-Options', value: 'DENY' },
          // An operations console has nothing a crawler should ever see.
          { key: 'X-Robots-Tag', value: 'noindex, nofollow' },
          { key: 'Referrer-Policy', value: 'same-origin' },
        ],
      },
    ];
  },
};

export default config;
