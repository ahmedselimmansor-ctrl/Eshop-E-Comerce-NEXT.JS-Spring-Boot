'use client';

import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MapPin, Pencil, Plus, Star, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { ApiError, addressRequestSchema, type Address, type AddressRequest } from '@eshop/api-client';
import { Button } from '@/components/ui/button';
import { Badge, Card, Field, Input, Select, Skeleton } from '@/components/ui/primitives';
import { browserApi } from '@/lib/api-browser';
import { EGYPT_GOVERNORATES } from '@/lib/utils';

interface Labels {
  addresses: string;
  addAddress: string;
  editAddress: string;
  noAddresses: string;
  defaultShipping: string;
  makeDefault: string;
  deleteConfirm: string;
  label: string;
  labelPlaceholder: string;
  recipientName: string;
  phone: string;
  governorate: string;
  selectGovernorate: string;
  city: string;
  area: string;
  street: string;
  building: string;
  floor: string;
  apartment: string;
  landmark: string;
  landmarkPlaceholder: string;
  save: string;
  cancel: string;
  edit: string;
  delete: string;
  optional: string;
  somethingWentWrong: string;
}

export function AddressesView({ labels }: { labels: Labels }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Address | null>(null);
  const [adding, setAdding] = useState(false);

  const { data: addresses, isLoading } = useQuery({
    queryKey: ['addresses'],
    queryFn: () => browserApi().account.addresses(),
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['addresses'] });
    setEditing(null);
    setAdding(false);
  }

  const remove = useMutation({
    mutationFn: (id: string) => browserApi().account.deleteAddress(id),
    onSuccess: refresh,
  });

  const makeDefault = useMutation({
    mutationFn: (id: string) => browserApi().account.makeAddressDefault(id, 'shipping'),
    onSuccess: refresh,
  });

  if (isLoading) return <Skeleton className="h-64 w-full" />;

  if (adding || editing) {
    return (
      <AddressForm
        initial={editing}
        labels={labels}
        onDone={refresh}
        onCancel={() => {
          setAdding(false);
          setEditing(null);
        }}
      />
    );
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-semibold">{labels.addresses}</h2>
        <Button size="sm" onClick={() => setAdding(true)}>
          <Plus aria-hidden />
          {labels.addAddress}
        </Button>
      </div>

      {!addresses || addresses.length === 0 ? (
        <Card className="flex flex-col items-center gap-3 p-12 text-center">
          <MapPin className="size-10 text-[var(--muted-foreground)]" aria-hidden />
          <p className="text-sm text-[var(--muted-foreground)]">{labels.noAddresses}</p>
        </Card>
      ) : (
        addresses.map((address) => (
          <Card key={address.id} className="p-4">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <p className="font-medium">{address.recipientName}</p>
                  {address.label ? <Badge>{address.label}</Badge> : null}
                  {address.defaultShipping ? (
                    <Badge tone="brand">{labels.defaultShipping}</Badge>
                  ) : null}
                </div>

                <p className="mt-1 text-sm text-[var(--muted-foreground)]">{address.formatted}</p>
                <p className="numeric text-sm text-[var(--muted-foreground)]">{address.phone}</p>
              </div>

              <div className="flex shrink-0 gap-1">
                {!address.defaultShipping ? (
                  <button
                    type="button"
                    onClick={() => makeDefault.mutate(address.id)}
                    className="rounded p-2 text-[var(--muted-foreground)] hover:bg-[var(--muted)]"
                    aria-label={labels.makeDefault}
                    title={labels.makeDefault}
                  >
                    <Star className="size-4" aria-hidden />
                  </button>
                ) : null}

                <button
                  type="button"
                  onClick={() => setEditing(address)}
                  className="rounded p-2 text-[var(--muted-foreground)] hover:bg-[var(--muted)]"
                  aria-label={labels.edit}
                >
                  <Pencil className="size-4" aria-hidden />
                </button>

                <button
                  type="button"
                  onClick={() => {
                    if (window.confirm(labels.deleteConfirm)) remove.mutate(address.id);
                  }}
                  className="rounded p-2 text-danger-500 hover:bg-danger-500/10"
                  aria-label={labels.delete}
                >
                  <Trash2 className="size-4" aria-hidden />
                </button>
              </div>
            </div>
          </Card>
        ))
      )}
    </div>
  );
}

function AddressForm({
  initial,
  labels,
  onDone,
  onCancel,
}: {
  initial: Address | null;
  labels: Labels;
  onDone: () => void;
  onCancel: () => void;
}) {
  const form = useForm<AddressRequest>({
    resolver: zodResolver(addressRequestSchema),
    defaultValues: initial
      ? {
          label: initial.label ?? '',
          recipientName: initial.recipientName,
          phone: initial.phone,
          governorate: initial.governorate,
          city: initial.city,
          area: initial.area ?? '',
          street: initial.street,
          building: initial.building ?? '',
          floor: initial.floor ?? '',
          apartment: initial.apartment ?? '',
          landmark: initial.landmark ?? '',
        }
      : { recipientName: '', phone: '', governorate: '', city: '', street: '' },
  });

  const save = useMutation({
    mutationFn: (values: AddressRequest) =>
      initial
        ? browserApi().account.updateAddress(initial.id, values)
        : browserApi().account.createAddress(values),
    onSuccess: onDone,
    onError: (error) => {
      if (error instanceof ApiError) {
        for (const [field, message] of Object.entries(error.fieldErrors())) {
          form.setError(field as keyof AddressRequest, { message });
        }
      }
    },
  });

  return (
    <Card className="p-5">
      <h2 className="font-semibold">{initial ? labels.editAddress : labels.addAddress}</h2>

      <form
        onSubmit={form.handleSubmit((values) => save.mutate(values))}
        className="mt-4 grid gap-4 sm:grid-cols-2"
        noValidate
      >
        <Field id="label" label={labels.label} hint={labels.labelPlaceholder}>
          <Input id="label" {...form.register('label')} />
        </Field>

        <Field
          id="recipientName"
          label={labels.recipientName}
          error={form.formState.errors.recipientName?.message}
          required
        >
          <Input id="recipientName" autoComplete="name" {...form.register('recipientName')} />
        </Field>

        <Field
          id="phone"
          label={labels.phone}
          error={form.formState.errors.phone?.message}
          required
        >
          <Input
            id="phone"
            type="tel"
            inputMode="tel"
            dir="ltr"
            autoComplete="tel"
            invalid={Boolean(form.formState.errors.phone)}
            {...form.register('phone')}
          />
        </Field>

        <Field
          id="governorate"
          label={labels.governorate}
          error={form.formState.errors.governorate?.message}
          required
        >
          {/* A fixed list, not free text: the API matches shipping zones on this exact string, and
              a typo would silently make the address undeliverable. */}
          <Select id="governorate" {...form.register('governorate')}>
            <option value="">{labels.selectGovernorate}</option>
            {EGYPT_GOVERNORATES.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </Select>
        </Field>

        <Field id="city" label={labels.city} error={form.formState.errors.city?.message} required>
          <Input id="city" {...form.register('city')} />
        </Field>

        <Field id="area" label={labels.area} hint={labels.optional}>
          <Input id="area" {...form.register('area')} />
        </Field>

        <div className="sm:col-span-2">
          <Field
            id="street"
            label={labels.street}
            error={form.formState.errors.street?.message}
            required
          >
            <Input id="street" autoComplete="street-address" {...form.register('street')} />
          </Field>
        </div>

        <Field id="building" label={labels.building} hint={labels.optional}>
          <Input id="building" {...form.register('building')} />
        </Field>

        <Field id="floor" label={labels.floor} hint={labels.optional}>
          <Input id="floor" {...form.register('floor')} />
        </Field>

        <Field id="apartment" label={labels.apartment} hint={labels.optional}>
          <Input id="apartment" {...form.register('apartment')} />
        </Field>

        <Field id="landmark" label={labels.landmark} hint={labels.landmarkPlaceholder}>
          <Input id="landmark" {...form.register('landmark')} />
        </Field>

        {save.isError && !(save.error instanceof ApiError && save.error.is('VALIDATION_ERROR')) ? (
          <p role="alert" className="sm:col-span-2 rounded-md bg-danger-500/10 p-3 text-sm text-danger-600">
            {labels.somethingWentWrong}
          </p>
        ) : null}

        <div className="flex gap-2 sm:col-span-2">
          <Button type="submit" loading={save.isPending}>
            {labels.save}
          </Button>
          <Button type="button" variant="outline" onClick={onCancel}>
            {labels.cancel}
          </Button>
        </div>
      </form>
    </Card>
  );
}
