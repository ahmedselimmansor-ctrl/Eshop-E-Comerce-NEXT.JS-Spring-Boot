import { defineWorkspace } from 'vitest/config';
import { resolve } from 'node:path';

const stub = (name: string) => resolve(__dirname, 'test-stubs', name);

/**
 * Projects split by environment and by app.
 *
 * Both apps use `@` for their own `src`, so they cannot share one alias map - pointing it at the
 * admin made the storefront import the admin's helpers and vice versa. Each app therefore gets its
 * own project rather than one "components" project with a guessed alias.
 *
 * The Node project holds the contract and formatting tests: a DOM would only slow them down.
 */
export default defineWorkspace([
  {
    extends: './vitest.config.ts',
    test: {
      name: 'unit',
      environment: 'node',
      include: ['{apps,packages}/*/test/**/*.test.ts'],
    },
  },
  {
    extends: './vitest.config.ts',
    resolve: { alias: { '@': resolve(__dirname, 'apps/admin/src') } },
    test: {
      name: 'admin',
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./vitest.setup.ts'],
      include: ['apps/admin/test/**/*.test.tsx'],
    },
  },
  {
    extends: './vitest.config.ts',
    resolve: {
      alias: {
        // Next's image component needs the optimiser and a loader config that no unit test has, and
        // next-intl's Link needs the routing table. Both are stubbed so these stay tests of the
        // component rather than of the framework.
        'next/image': stub('next-image.tsx'),
        '@/i18n/routing': stub('routing.tsx'),
        '@': resolve(__dirname, 'apps/storefront/src'),
      },
    },
    test: {
      name: 'storefront',
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./vitest.setup.ts'],
      include: ['apps/storefront/test/**/*.test.tsx'],
    },
  },
]);
