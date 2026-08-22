import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

/**
 * Unmounts everything between tests.
 *
 * Without it, `getByRole` searches the leftover DOM of previous tests and a query that should match
 * one element matches several, which fails in a way that points at the wrong test.
 */
afterEach(() => cleanup());
