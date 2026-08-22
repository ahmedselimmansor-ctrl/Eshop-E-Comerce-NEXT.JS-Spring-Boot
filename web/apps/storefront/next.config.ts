import type { NextConfig } from 'next';
import createNextIntlPlugin from 'next-intl/plugin';

const withNextIntl = createNextIntlPlugin('./src/i18n/request.ts');

const cdnHost = process.env.NEXT_PUBLIC_CDN_BASE_URL
  ? new URL(process.env.NEXT_PUBLIC_CDN_BASE_URL).hostname
  : 'localhost';

const config: NextConfig = {
  reactStrictMode: true,

  // Compiled into a standalone bundle so the runtime image carries only what is actually imported
  // - roughly 150 MB instead of the full node_modules tree.
  output: 'standalone',

  // The api-client is TypeScript source in a sibling workspace, not a built package; Next has to
  // compile it rather than treat it as an opaque dependency.
  transpilePackages: ['@eshop/api-client'],

  images: {
    remotePatterns: [
      { protocol: 'https', hostname: cdnHost },
      { protocol: 'https', hostname: 'storage.googleapis.com' },
      { protocol: 'http', hostname: 'localhost' },
    ],
    formats: ['image/avif', 'image/webp'],
    // Matched to the breakpoints the product grid actually uses - extra entries generate images
    // nobody requests.
    deviceSizes: [360, 640, 768, 1024, 1280, 1536],
    imageSizes: [64, 96, 128, 256, 384],
    minimumCacheTTL: 60 * 60 * 24 * 30,
  },

  experimental: {
    optimizePackageImports: ['lucide-react', '@radix-ui/react-dialog'],
  },

  // The API is reached server-side through the cluster-internal address, skipping the external
  // load balancer entirely for server components and route handlers.
  env: {
    API_INTERNAL_BASE_URL:
      process.env.API_INTERNAL_BASE_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL ?? '',
  },

  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
          { key: 'X-Frame-Options', value: 'DENY' },
          {
            key: 'Permissions-Policy',
            value: 'camera=(), microphone=(), geolocation=(self), payment=(self)',
          },
          {
            key: 'Strict-Transport-Security',
            value: 'max-age=63072000; includeSubDomains; preload',
          },
        ],
      },
      {
        // Immutable build assets - content-hashed filenames, so a year is safe.
        source: '/_next/static/:path*',
        headers: [{ key: 'Cache-Control', value: 'public, max-age=31536000, immutable' }],
      },
    ];
  },

  poweredByHeader: false,
  compress: true,
};

export default withNextIntl(config);
