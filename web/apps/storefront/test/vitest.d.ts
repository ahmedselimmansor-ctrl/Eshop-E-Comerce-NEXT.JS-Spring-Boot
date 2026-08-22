/**
 * Makes jest-dom's matchers visible to `tsc`.
 *
 * The runtime augmentation happens in the workspace's `vitest.setup.ts`, which sits outside this
 * app's compilation, so the type checker would otherwise reject `toBeInTheDocument` in tests that
 * run perfectly well.
 */
import '@testing-library/jest-dom/vitest';
