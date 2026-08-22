import type { ReactNode } from 'react';
import { AuthGuard } from '@/components/layout/auth-guard';
import { ConsoleShell } from '@/components/layout/console-shell';

/**
 * Everything behind sign-in.
 *
 * A route group rather than a path segment: the URLs stay `/orders`, `/products` and so on, while
 * `/login` sits outside this layout and is reachable without a session.
 */
export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return (
    <AuthGuard>
      <ConsoleShell>{children}</ConsoleShell>
    </AuthGuard>
  );
}
