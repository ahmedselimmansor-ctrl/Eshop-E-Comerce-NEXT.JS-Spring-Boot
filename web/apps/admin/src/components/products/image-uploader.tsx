'use client';

import { useRef, useState } from 'react';
import { GripVertical, Trash2, Upload } from 'lucide-react';
import { ApiError } from '@eshop/api-client';
import { adminApi } from '@/lib/api';
import { Button } from '@/components/ui/primitives';

/** What the API accepts; anything else is rejected server-side, so it is filtered here first. */
const ACCEPTED = ['image/jpeg', 'image/png', 'image/webp', 'image/avif'];
const MAX_BYTES = 10 * 1024 * 1024;

/**
 * Product images, uploaded straight to storage.
 *
 * The bytes never pass through the API. It hands back a pre-signed target, the browser PUTs the file
 * to it directly, and only the resulting public URL is submitted with the product. That keeps a
 * 10 MB upload off the request thread and out of the pod's memory.
 *
 * The first image is the primary one, which is why order is editable and stated rather than implied.
 */
export function ImageUploader({
  images,
  onChange,
  disabled,
}: {
  images: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function upload(files: FileList | null) {
    if (!files || files.length === 0) return;

    setError(null);
    setUploading(true);

    try {
      const uploaded: string[] = [];

      for (const file of Array.from(files)) {
        if (!ACCEPTED.includes(file.type)) {
          throw new Error(`${file.name} is not a supported image type`);
        }
        if (file.size > MAX_BYTES) {
          throw new Error(`${file.name} is larger than 10 MB`);
        }

        const target = await adminApi().media.uploadUrl(file.name, file.type, 'products');

        // A plain fetch, not the API client: this goes to the storage host, not to the API, and
        // must not carry the Authorization header or be parsed as a problem document.
        const response = await fetch(target.uploadUrl, {
          method: 'PUT',
          headers: { 'Content-Type': target.requiredContentType },
          body: file,
        });

        if (!response.ok) {
          throw new Error(`Upload failed for ${file.name} (${response.status})`);
        }
        uploaded.push(target.publicUrl);
      }

      onChange([...images, ...uploaded]);
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.detail
          : cause instanceof Error
            ? cause.message
            : 'Upload failed',
      );
    } finally {
      setUploading(false);
      if (inputRef.current) inputRef.current.value = '';
    }
  }

  function move(from: number, to: number) {
    if (to < 0 || to >= images.length) return;
    const next = [...images];
    const [moved] = next.splice(from, 1);
    if (moved === undefined) return;
    next.splice(to, 0, moved);
    onChange(next);
  }

  return (
    <div className="space-y-3">
      {images.length > 0 ? (
        <ol className="space-y-1.5">
          {images.map((url, index) => (
            <li key={url} className="flex items-center gap-2 text-xs">
              <GripVertical className="size-3.5 shrink-0 text-[var(--muted-foreground)]" aria-hidden />

              <span className="w-16 shrink-0 text-[var(--muted-foreground)]">
                {index === 0 ? 'Primary' : `#${index + 1}`}
              </span>

              {/* eslint-disable-next-line @next/next/no-img-element -- uploads live on an arbitrary
                  storage host, which the image optimiser's allowlist cannot cover. */}
              <img src={url} alt="" className="size-10 shrink-0 rounded object-cover" />

              <span className="min-w-0 flex-1 truncate" title={url}>
                {url}
              </span>

              {!disabled ? (
                <>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label="Move up"
                    disabled={index === 0}
                    onClick={() => move(index, index - 1)}
                  >
                    ↑
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label="Move down"
                    disabled={index === images.length - 1}
                    onClick={() => move(index, index + 1)}
                  >
                    ↓
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label="Remove image"
                    onClick={() => onChange(images.filter((_, i) => i !== index))}
                  >
                    <Trash2 aria-hidden />
                  </Button>
                </>
              ) : null}
            </li>
          ))}
        </ol>
      ) : (
        <p className="text-xs text-[var(--muted-foreground)]">
          No images yet. The first one uploaded becomes the primary image.
        </p>
      )}

      {!disabled ? (
        <div className="flex items-center gap-2">
          <input
            ref={inputRef}
            type="file"
            multiple
            accept={ACCEPTED.join(',')}
            className="hidden"
            onChange={(event) => void upload(event.target.files)}
          />
          <Button
            type="button"
            variant="outline"
            loading={uploading}
            onClick={() => inputRef.current?.click()}
          >
            <Upload aria-hidden />
            {uploading ? 'Uploading…' : 'Upload images'}
          </Button>
          <span className="text-xs text-[var(--muted-foreground)]">
            JPEG, PNG, WebP or AVIF, up to 10 MB each.
          </span>
        </div>
      ) : null}

      {error ? (
        <p role="alert" className="text-xs text-danger-500">
          {error}
        </p>
      ) : null}
    </div>
  );
}
