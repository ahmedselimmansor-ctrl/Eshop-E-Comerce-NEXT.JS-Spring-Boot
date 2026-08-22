import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/widgets/async_view.dart';
import '../data/account_repository.dart';

/// Saved delivery addresses.
class AddressesScreen extends ConsumerWidget {
  const AddressesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final addresses = ref.watch(myAddressesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Delivery addresses')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _addAddress(context, ref),
        icon: const Icon(Icons.add),
        label: const Text('Add'),
      ),
      body: AsyncView(
        value: addresses,
        onRetry: () => ref.invalidate(myAddressesProvider),
        builder: (list) => list.isEmpty
            ? const EmptyState(
                icon: Icons.location_off_outlined,
                title: 'No addresses yet',
                hint: 'Add one so checkout knows where to deliver.',
              )
            : ListView.separated(
                padding: const EdgeInsets.only(bottom: 88),
                itemCount: list.length,
                separatorBuilder: (context, index) => const Divider(height: 1),
                itemBuilder: (context, index) {
                  final address = list[index];
                  return ListTile(
                    leading: const Icon(Icons.location_on_outlined),
                    title: Text(address.recipientName),
                    subtitle: Text('${address.summary}\n${address.phone}'),
                    isThreeLine: true,
                    trailing: address.isDefaultShipping
                        ? const Chip(label: Text('Default'))
                        : null,
                  );
                },
              ),
      ),
    );
  }

  Future<void> _addAddress(BuildContext context, WidgetRef ref) async {
    final added = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (context) => const _AddAddressSheet(),
    );

    if (added ?? false) ref.invalidate(myAddressesProvider);
  }
}

class _AddAddressSheet extends ConsumerStatefulWidget {
  const _AddAddressSheet();

  @override
  ConsumerState<_AddAddressSheet> createState() => _AddAddressSheetState();
}

class _AddAddressSheetState extends ConsumerState<_AddAddressSheet> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _phone = TextEditingController();
  final _city = TextEditingController();
  final _street = TextEditingController();

  /// Arabic names, because that is how the shipping zones store them. An English spelling matches
  /// no zone, and checkout then offers no delivery options at all.
  static const _governorates = [
    'القاهرة', 'الجيزة', 'القليوبية', 'الإسكندرية', 'البحيرة', 'كفر الشيخ',
    'الغربية', 'المنوفية', 'الدقهلية', 'دمياط', 'الشرقية', 'بورسعيد',
    'الإسماعيلية', 'السويس', 'شمال سيناء', 'جنوب سيناء', 'الفيوم', 'بني سويف',
    'المنيا', 'أسيوط', 'سوهاج', 'قنا', 'الأقصر', 'أسوان',
    'البحر الأحمر', 'الوادي الجديد', 'مطروح',
  ];

  String _governorate = _governorates.first;
  bool _busy = false;

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    _city.dispose();
    _street.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 16,
        // Lifts the sheet above the keyboard, which otherwise covers the save button.
        bottom: MediaQuery.viewInsetsOf(context).bottom + 16,
      ),
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('New address', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),

            TextFormField(
              controller: _name,
              decoration: const InputDecoration(labelText: 'Recipient name'),
              validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
            ),
            const SizedBox(height: 8),
            TextFormField(
              controller: _phone,
              keyboardType: TextInputType.phone,
              decoration: const InputDecoration(
                labelText: 'Phone',
                hintText: '+20 100 000 0000',
              ),
              validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
            ),
            const SizedBox(height: 8),
            DropdownButtonFormField<String>(
              value: _governorate,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'Governorate'),
              items: [
                for (final governorate in _governorates)
                  DropdownMenuItem(value: governorate, child: Text(governorate)),
              ],
              onChanged: (value) => setState(() => _governorate = value!),
            ),
            const SizedBox(height: 8),
            TextFormField(
              controller: _city,
              decoration: const InputDecoration(labelText: 'City or district'),
              validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
            ),
            const SizedBox(height: 8),
            TextFormField(
              controller: _street,
              decoration: const InputDecoration(labelText: 'Street and building'),
              validator: (v) => (v == null || v.trim().isEmpty) ? 'Required' : null,
            ),
            const SizedBox(height: 16),

            FilledButton(
              onPressed: _busy ? null : _save,
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
              child: _busy
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('Save address'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _save() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;

    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);

    try {
      await ref.read(accountRepositoryProvider).addAddress(
            recipientName: _name.text.trim(),
            phone: _phone.text.trim(),
            governorate: _governorate,
            city: _city.text.trim(),
            street: _street.text.trim(),
          );
      navigator.pop(true);
    } catch (error) {
      if (!mounted) return;
      setState(() => _busy = false);
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            error is ApiException ? error.detail : 'Could not save this address',
          ),
        ),
      );
    }
  }
}
