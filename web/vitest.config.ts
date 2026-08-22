import { defineConfig } from 'vitest/config';
import { resolve } from 'node:path';

/**
 * One Vitest run for the whole workspace.
 *
 * Node environment, not jsdom: what is worth testing here is the code that fails silently rather
 * than visibly. A Zod schema that no longer matches the API turns a field into `undefined` and a
 * price into a blank space, and a component test with a mocked client would not notice, because the
 * mock is written from the same wrong assumption as the schema.
 */
export default defineConfig({
  test: {
    environment: 'node',
    include: ['{apps,packages}/*/src/**/*.test.ts', '{apps,packages}/*/test/**/*.test.ts'],
    passWithNoTests: false,
  },
  resolve: {
    alias: {
      '@eshop/api-client': resolve(__dirname, 'packages/api-client/src/index.ts'),
      '@eshop/api-client/schemas': resolve(__dirname, 'packages/api-client/src/schemas/index.ts'),
    },
  },
});
