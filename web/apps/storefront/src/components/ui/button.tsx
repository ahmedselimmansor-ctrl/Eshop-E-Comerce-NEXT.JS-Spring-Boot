import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { Loader2 } from 'lucide-react';
import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { cn } from '@/lib/utils';

const buttonVariants = cva(
  // Layout, focus, and disabled behaviour shared by every variant. `gap-2` rather than a margin on
  // the icon so it works identically in RTL without a directional override.
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-[var(--radius-control)] ' +
    'text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 ' +
    'focus-visible:ring-[var(--ring)] focus-visible:ring-offset-2 disabled:pointer-events-none ' +
    'disabled:opacity-50 [&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        primary:
          'bg-brand-600 text-white hover:bg-brand-700 active:bg-brand-800 shadow-sm',
        secondary:
          'bg-[var(--muted)] text-[var(--foreground)] hover:bg-[var(--border)]',
        outline:
          'border border-[var(--border)] bg-[var(--background)] hover:bg-[var(--muted)]',
        ghost: 'hover:bg-[var(--muted)]',
        // Reserved for irreversible actions: cancelling an order, deleting an address.
        danger: 'bg-danger-500 text-white hover:bg-danger-600 shadow-sm',
        link: 'text-brand-600 underline-offset-4 hover:underline',
      },
      size: {
        sm: 'h-9 px-3',
        // 44px - the minimum comfortable tap target on a phone, which is where most of this
        // store's traffic comes from.
        md: 'h-11 px-5',
        lg: 'h-12 px-8 text-base',
        icon: 'h-11 w-11',
      },
      fullWidth: {
        true: 'w-full',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
);

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  /** Renders the child element instead of a `<button>` - for links that look like buttons. */
  asChild?: boolean;
  loading?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, fullWidth, asChild, loading, children, disabled, ...props }, ref) => {
    const classes = cn(buttonVariants({ variant, size, fullWidth }), className);

    /*
     * `asChild` renders the caller's element - usually a Link - with the button's styling. Radix's
     * Slot requires exactly one child, so the spinner must not be emitted here: even a `null` from
     * a false branch counts as a second child and fails at render with "Slot failed to slot onto
     * its children".
     *
     * A link has nothing to submit and therefore no loading state, so there is nothing to lose.
     */
    if (asChild) {
      return (
        <Slot ref={ref} className={classes} {...props}>
          {children}
        </Slot>
      );
    }

    return (
      <button
        ref={ref}
        className={classes}
        // A loading button stays disabled so a double tap cannot submit twice - which on checkout
        // would mean a second order attempt.
        disabled={disabled || loading}
        aria-busy={loading || undefined}
        {...props}
      >
        {loading ? <Loader2 className="animate-spin" aria-hidden /> : null}
        {children}
      </button>
    );
  },
);

Button.displayName = 'Button';
export { buttonVariants };
