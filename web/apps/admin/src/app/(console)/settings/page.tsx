'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ApiError } from '@eshop/api-client';
import { adminApi } from '@/lib/api';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  EmptyState,
  ErrorState,
  Input,
  Skeleton,
} from '@/components/ui/primitives';
import { hasPermission } from '@/stores/auth-store';

/**
 * Store settings.
 *
 * Rendered from whatever the API returns rather than from a hard-coded form, so adding a setting on
 * the server does not need a matching release here. Values are edited as JSON text because that is
 * what they are — typing each one would mean a schema this screen has no way to know.
 *
 * Only changed rows are submitted, so two operators editing different settings do not overwrite each
 * other's work.
 */
export default function SettingsPage() {
  const queryClient = useQueryClient();
  const canWrite = hasPermission('settings:write');

  const settings = useQuery({
    queryKey: ['admin', 'settings'],
    queryFn: () => adminApi().settings.list(),
  });

  const [edits, setEdits] = useState<Record<string, string>>({});

  const save = useMutation({
    mutationFn: () => {
      const payload: Record<string, unknown> = {};

      for (const [key, raw] of Object.entries(edits)) {
        // A setting may hold a number, a boolean, an object, or a plain string. Parsing as JSON
        // first preserves the type the API expects; anything that is not valid JSON is sent as the
        // string it is, which is the common case for a store name or an address.
        try {
          payload[key] = JSON.parse(raw);
        } catch {
          payload[key] = raw;
        }
      }
      return adminApi().settings.update(payload);
    },
    onSuccess: () => {
      setEdits({});
      void queryClient.invalidateQueries({ queryKey: ['admin', 'settings'] });
    },
  });

  if (settings.isLoading) return <Skeleton className="h-96 w-full" />;
  if (settings.isError) {
    return <ErrorState onRetry={() => void settings.refetch()} />;
  }

  const rows = settings.data ?? [];
  const dirty = Object.keys(edits).length;

  /** Renders a value for editing: strings as-is, everything else as JSON. */
  const asText = (value: unknown) =>
    typeof value === 'string' ? value : JSON.stringify(value ?? '');

  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold">Settings</h1>

        {canWrite ? (
          <div className="flex items-center gap-2">
            {dirty > 0 ? (
              <>
                <span className="numeric text-xs text-[var(--muted-foreground)]">
                  {dirty} changed
                </span>
                <Button variant="ghost" size="sm" onClick={() => setEdits({})}>
                  Discard
                </Button>
              </>
            ) : null}
            <Button disabled={dirty === 0} loading={save.isPending} onClick={() => save.mutate()}>
              Save changes
            </Button>
          </div>
        ) : null}
      </div>

      {save.error ? (
        <p role="alert" className="text-xs text-danger-500">
          {save.error instanceof ApiError ? save.error.detail : 'Could not save the settings.'}
        </p>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle>Store configuration</CardTitle>
        </CardHeader>

        {rows.length === 0 ? (
          <EmptyState title="No settings defined" />
        ) : (
          <CardBody className="space-y-4">
            {rows.map((row) => {
              const current = edits[row.key] ?? asText(row.value);
              const changed = row.key in edits;

              return (
                <div key={row.key} className="space-y-1">
                  <label
                    htmlFor={`setting-${row.key}`}
                    className="flex flex-wrap items-center gap-2 text-xs font-medium"
                  >
                    <span className="numeric">{row.key}</span>
                    {/* A public setting is served to every visitor — worth flagging before someone
                        puts an internal note in one. */}
                    {row.publicSetting ? <Badge tone="brand">Public</Badge> : null}
                    {changed ? <Badge tone="warning">Modified</Badge> : null}
                  </label>

                  <Input
                    id={`setting-${row.key}`}
                    value={current}
                    disabled={!canWrite}
                    onChange={(event) =>
                      setEdits((currentEdits) => ({
                        ...currentEdits,
                        [row.key]: event.target.value,
                      }))
                    }
                  />

                  {row.description ? (
                    <p className="text-xs text-[var(--muted-foreground)]">{row.description}</p>
                  ) : null}
                </div>
              );
            })}
          </CardBody>
        )}
      </Card>

      {!canWrite ? (
        <p className="text-xs text-[var(--muted-foreground)]">
          Your role can read these settings but not change them.
        </p>
      ) : null}
    </div>
  );
}
