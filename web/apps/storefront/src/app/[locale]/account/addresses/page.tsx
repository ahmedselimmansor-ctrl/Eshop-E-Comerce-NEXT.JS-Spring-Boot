import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { AddressesView } from '@/components/account/addresses-view';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'account' });
  return { title: t('addresses'), robots: { index: false, follow: false } };
}

export default async function AddressesPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('account');
  const address = await getTranslations('address');
  const auth = await getTranslations('auth');
  const common = await getTranslations('common');

  return (
    <AddressesView
      labels={{
        addresses: t('addresses'),
        addAddress: t('addAddress'),
        editAddress: t('editAddress'),
        noAddresses: t('noAddresses'),
        defaultShipping: t('defaultShipping'),
        makeDefault: t('makeDefault'),
        deleteConfirm: t('deleteAddressConfirm'),
        label: address('label'),
        labelPlaceholder: address('labelPlaceholder'),
        recipientName: address('recipientName'),
        phone: auth('phone'),
        governorate: address('governorate'),
        selectGovernorate: address('selectGovernorate'),
        city: address('city'),
        area: address('area'),
        street: address('street'),
        building: address('building'),
        floor: address('floor'),
        apartment: address('apartment'),
        landmark: address('landmark'),
        landmarkPlaceholder: address('landmarkPlaceholder'),
        save: common('save'),
        cancel: common('cancel'),
        edit: common('edit'),
        delete: common('delete'),
        optional: common('optional'),
        somethingWentWrong: common('somethingWentWrong'),
      }}
    />
  );
}
