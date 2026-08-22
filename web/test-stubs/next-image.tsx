/**
 * A plain `img` in place of `next/image`.
 *
 * The real component needs the Next image optimiser and a loader config that does not exist in a
 * unit test. What the tests care about is that the right `src` and `alt` reach the DOM.
 */
export default function Image({
  src,
  alt,
  ...rest
}: {
  src: string;
  alt: string;
  [key: string]: unknown;
}) {
  const { fill, priority, blurDataURL, placeholder, sizes, ...safe } =
    rest as Record<string, unknown>;
  void fill;
  void priority;
  void blurDataURL;
  void placeholder;
  void sizes;

  // eslint-disable-next-line @next/next/no-img-element, jsx-a11y/alt-text
  return <img src={src} alt={alt} {...(safe as Record<string, unknown>)} />;
}
