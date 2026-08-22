import { defineConfig } from 'vitest/config';
import { resolve } from 'node:path';

/** Only what every project shares; the `@` alias differs per app and lives in the workspace file. */
export default defineConfig({
  esbuild: { jsx: 'automatic' },
  resolve: {
    alias: {
      '@eshop/api-client': resolve(__dirname, 'packages/api-client/src/index.ts'),
      '@eshop/api-client/schemas': resolve(__dirname, 'packages/api-client/src/schemas/index.ts'),
    },
  },
});
