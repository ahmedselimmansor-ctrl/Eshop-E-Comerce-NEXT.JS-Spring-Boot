'use client';

import { cva, type VariantProps } from 'class-variance-authority';
import { Loader2 } from 'lucide-react';
import { Slot } from '@radix-ui/react-slot';
import {
  forwardRef,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import { cn } from '@/lib/utils';

/*
 * The console's building blocks.
 *
 * One file rather than one per component: these are small, they are always imported together, and
 * an operations tool has no need for the granular tree-shaking a public storefront does.
 */

// --------------------------------------------------------------------- button --

const buttonStyles = cva(
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-md font-medium ' +
    'transition-colors disabled:pointer-events-none disabled:opacity-50 ' +
    '[&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        primary: 'bg-brand-600 text-white hover:bg-brand-700',
        outline: 'border border-[var(--border)] bg-[var(--card)] hover:bg-[var(--muted)]',
        ghost: 'hover:bg-[var(--muted)]',
        danger: 'bg-danger-500 text-white hover:brightness-95',
      },
      size: {
        sm: 'h-8 px-2.5 text-xs',
        md: 'h-9 px-3',
        lg: 'h-10 px-4',
        icon: 'size-9',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
);

interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonStyles> {
  asChild?: boolean;
  loading?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild, loading, children, disabled, ...props }, ref) => {
    const classes = cn(buttonStyles({ variant, size }), className);

    // `asChild` renders the child element instead of a <button>. Slot accepts exactly one child, so
    // the spinner branch below is deliberately not reachable here.
    if (asChild) {
      return (
        <Slot ref={ref} className={classes} {...props}>
          {children}
        </Slot>
      );
    }

    return (
      <button ref={ref} className={classes} disabled={disabled || loading} {...props}>
        {loading ? <Loader2 className="animate-spin" aria-hidden /> : null}
        {children}
      </button>
    );
  },
);
Button.displayName = 'Button';

// ----------------------------------------------------------------------- card --

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('rounded-lg border border-[var(--border)] bg-[var(--card)]', className)}
      {...props}
    />
  );
}

export function CardHeader({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('border-b border-[var(--border)] px-4 py-3', className)} {...props} />;
}

export function CardTitle({ className, ...props }: HTMLAttributes<HTMLHeadingElement>) {
  return <h2 className={cn('text-sm font-semibold', className)} {...props} />;
}

export function CardBody({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('p-4', className)} {...props} />;
}

// ---------------------------------------------------------------------- badge --

const badgeStyles = cva(
  'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap',
  {
    variants: {
      tone: {
        neutral: 'bg-[var(--muted)] text-[var(--muted-foreground)]',
        brand: 'bg-brand-50 text-brand-700',
        success: 'bg-success-500/15 text-success-500',
        warning: 'bg-warning-500/20 text-warning-500',
        danger: 'bg-danger-500/15 text-danger-500',
      },
    },
    defaultVariants: { tone: 'neutral' },
  },
);

export function Badge({
  className,
  tone,
  ...props
}: HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badgeStyles>) {
  return <span className={cn(badgeStyles({ tone }), className)} {...props} />;
}

// --------------------------------------------------------------------- inputs --

const fieldStyles =
  'h-9 w-full rounded-md border border-[var(--border)] bg-[var(--card)] px-2.5 text-sm ' +
  'placeholder:text-[var(--muted-foreground)] disabled:opacity-50';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input ref={ref} className={cn(fieldStyles, className)} {...props} />
  ),
);
Input.displayName = 'Input';

export const Textarea = forwardRef<
  HTMLTextAreaElement,
  TextareaHTMLAttributes<HTMLTextAreaElement>
>(({ className, ...props }, ref) => (
  <textarea
    ref={ref}
    className={cn(fieldStyles, 'h-auto min-h-20 py-2 leading-relaxed', className)}
    {...props}
  />
));
Textarea.displayName = 'Textarea';

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  ({ className, ...props }, ref) => (
    <select ref={ref} className={cn(fieldStyles, 'pe-8', className)} {...props} />
  ),
);
Select.displayName = 'Select';

/** A labelled control. The label is always rendered - a placeholder is not a label. */
export function Field({
  id,
  label,
  hint,
  error,
  required,
  className,
  children,
}: {
  id: string;
  label: string;
  hint?: string;
  error?: string;
  required?: boolean;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className={cn('space-y-1', className)}>
      <label htmlFor={id} className="block text-xs font-medium">
        {label}
        {required ? (
          <span className="ms-0.5 text-danger-500" aria-hidden>
            *
          </span>
        ) : null}
      </label>

      {children}

      {error ? (
        <p role="alert" className="text-xs text-danger-500">
          {error}
        </p>
      ) : hint ? (
        <p className="text-xs text-[var(--muted-foreground)]">{hint}</p>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------------- table --

export function Table({ className, ...props }: HTMLAttributes<HTMLTableElement>) {
  return (
    // Horizontally scrollable rather than squeezed: an order row has more columns than a laptop
    // has width, and shrinking the text until it fits is how tables become unreadable.
    <div className="w-full overflow-x-auto">
      <table className={cn('w-full text-sm', className)} {...props} />
    </div>
  );
}

export function Th({ className, ...props }: HTMLAttributes<HTMLTableCellElement>) {
  return (
    <th
      className={cn(
        'border-b border-[var(--border)] px-3 py-2 text-start text-xs font-medium',
        'text-[var(--muted-foreground)] whitespace-nowrap',
        className,
      )}
      {...props}
    />
  );
}

export function Td({ className, ...props }: HTMLAttributes<HTMLTableCellElement>) {
  return (
    <td className={cn('border-b border-[var(--border)] px-3 py-2 align-middle', className)} {...props} />
  );
}

// -------------------------------------------------------------------- feedback --

export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('animate-pulse rounded bg-[var(--muted)]', className)} {...props} />;
}

/** Shown in place of a table body when a filter matches nothing. */
export function EmptyState({
  title,
  hint,
  action,
}: {
  title: string;
  hint?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center gap-2 px-4 py-12 text-center">
      <p className="font-medium">{title}</p>
      {hint ? <p className="text-xs text-[var(--muted-foreground)]">{hint}</p> : null}
      {action}
    </div>
  );
}

export function ErrorState({ onRetry, message }: { onRetry?: () => void; message?: string }) {
  return (
    <div className="flex flex-col items-center gap-3 px-4 py-12 text-center">
      <p className="font-medium">{message ?? 'Something went wrong'}</p>
      {onRetry ? (
        <Button variant="outline" size="sm" onClick={onRetry}>
          Retry
        </Button>
      ) : null}
    </div>
  );
}

export function Separator({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('h-px bg-[var(--border)]', className)} {...props} />;
}
