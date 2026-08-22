import { cva, type VariantProps } from 'class-variance-authority';
import { forwardRef, type HTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { cn } from '@/lib/utils';

/**
 * The small shared primitives - card, badge, input, textarea, select, skeleton.
 *
 * Grouped in one file because each is a handful of lines and they are almost always imported
 * together. The pieces with real behaviour (Button, dialogs, the quantity stepper) live in their
 * own files.
 */

// ------------------------------------------------------------------- surface --

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'rounded-[var(--radius-card)] border border-[var(--border)] bg-[var(--card)] shadow-[var(--shadow-card)]',
        className,
      )}
      {...props}
    />
  );
}

export function CardHeader({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col gap-1.5 p-5', className)} {...props} />;
}

export function CardTitle({ className, ...props }: HTMLAttributes<HTMLHeadingElement>) {
  return <h3 className={cn('text-lg font-semibold leading-tight', className)} {...props} />;
}

export function CardContent({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('p-5 pt-0', className)} {...props} />;
}

export function CardFooter({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex items-center gap-3 p-5 pt-0', className)} {...props} />;
}

// --------------------------------------------------------------------- badge --

const badgeVariants = cva(
  'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
  {
    variants: {
      tone: {
        neutral: 'bg-[var(--muted)] text-[var(--muted-foreground)]',
        brand: 'bg-brand-50 text-brand-700',
        // The one place the orange accent is used, so a discount is unmistakable.
        sale: 'bg-sale-500 text-white',
        success: 'bg-success-500/15 text-success-600',
        warning: 'bg-warning-500/20 text-yellow-800',
        danger: 'bg-danger-500/15 text-danger-600',
      },
    },
    defaultVariants: { tone: 'neutral' },
  },
);

export interface BadgeProps
  extends HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {}

export function Badge({ className, tone, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}

// --------------------------------------------------------------------- input --

const fieldStyles =
  'w-full rounded-[var(--radius-control)] border border-[var(--input)] bg-[var(--background)] ' +
  'px-3 py-2 text-sm placeholder:text-[var(--muted-foreground)] focus-visible:outline-none ' +
  'focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:border-transparent ' +
  'disabled:cursor-not-allowed disabled:opacity-50';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  /** Renders the field in an error state and wires up `aria-invalid`. */
  invalid?: boolean;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ className, invalid, type = 'text', ...props }, ref) => (
    <input
      ref={ref}
      type={type}
      className={cn(fieldStyles, 'h-11', invalid && 'border-danger-500 focus-visible:ring-danger-500', className)}
      aria-invalid={invalid || undefined}
      {...props}
    />
  ),
);
Input.displayName = 'Input';

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ className, ...props }, ref) => (
    <textarea ref={ref} className={cn(fieldStyles, 'min-h-24 resize-y', className)} {...props} />
  ),
);
Textarea.displayName = 'Textarea';

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  ({ className, ...props }, ref) => (
    // A native select rather than a custom listbox: it is keyboard-accessible for free, and on a
    // phone it opens the platform picker, which is far better than any web reimplementation.
    <select ref={ref} className={cn(fieldStyles, 'h-11 pe-8', className)} {...props} />
  ),
);
Select.displayName = 'Select';

/**
 * A labelled form field with optional hint and error text.
 *
 * Wires `aria-describedby` so a screen reader announces the error with the field rather than
 * leaving it as unassociated text elsewhere on the page.
 */
export function Field({
  id,
  label,
  hint,
  error,
  required,
  children,
}: {
  id: string;
  label: string;
  hint?: string;
  error?: string;
  required?: boolean;
  children: ReactNode;
}) {
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
        {required ? <span className="text-danger-500 ms-1" aria-hidden>*</span> : null}
      </label>

      <div aria-describedby={cn(hintId, errorId) || undefined}>{children}</div>

      {hint && !error ? (
        <p id={hintId} className="text-xs text-[var(--muted-foreground)]">
          {hint}
        </p>
      ) : null}

      {error ? (
        <p id={errorId} role="alert" className="text-xs text-danger-500">
          {error}
        </p>
      ) : null}
    </div>
  );
}

// ------------------------------------------------------------------ skeleton --

/**
 * Loading placeholder.
 *
 * Sized to match the real content so the layout does not shift when data arrives - a spinner in a
 * zero-height box is what produces the jump that Cumulative Layout Shift measures.
 */
export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('skeleton rounded-md', className)} aria-hidden {...props} />;
}

export function Separator({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div role="separator" className={cn('h-px w-full bg-[var(--border)]', className)} {...props} />;
}
