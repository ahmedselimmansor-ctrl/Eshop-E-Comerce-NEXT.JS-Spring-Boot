import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/widgets/async_view.dart';
import '../../../core/widgets/money_text.dart';
import '../../auth/data/auth_repository.dart';
import '../../settings/locale_controller.dart';
import '../data/account_repository.dart';
import 'order_detail_screen.dart';

/// The customer's account: recent orders, addresses, language, sign out.
class AccountScreen extends ConsumerWidget {
  const AccountScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionControllerProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Account')),
      body: AsyncView(
        value: session,
        onRetry: () => ref.invalidate(sessionControllerProvider),
        builder: (account) {
          if (account == null) {
            return EmptyState(
              icon: Icons.person_outline,
              title: 'Not signed in',
              hint: 'Sign in to see your orders and addresses.',
              action: FilledButton(
                onPressed: () => context.push('/sign-in'),
                child: const Text('Sign in'),
              ),
            );
          }

          return ListView(
            children: [
              ListTile(
                leading: CircleAvatar(
                  child: Text(
                    (account.fullName?.isNotEmpty ?? false)
                        ? account.fullName![0].toUpperCase()
                        : account.email[0].toUpperCase(),
                  ),
                ),
                title: Text(account.fullName ?? account.email),
                subtitle: Text(account.email),
                trailing: account.emailVerified
                    ? null
                    : const Tooltip(
                        message: 'Email not verified',
                        child: Icon(Icons.error_outline, size: 18),
                      ),
              ),
              const Divider(),

              const _SectionHeader('Recent orders'),
              const _RecentOrders(),

              const Divider(),
              ListTile(
                leading: const Icon(Icons.location_on_outlined),
                title: const Text('Delivery addresses'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/account/addresses'),
              ),

              const _LanguageTile(),

              const Divider(),
              ListTile(
                leading: Icon(Icons.logout, color: Theme.of(context).colorScheme.error),
                title: Text(
                  'Sign out',
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
                onTap: () async {
                  await ref.read(sessionControllerProvider.notifier).logout();
                  if (context.mounted) context.go('/');
                },
              ),
            ],
          );
        },
      ),
    );
  }
}

class _RecentOrders extends ConsumerWidget {
  const _RecentOrders();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final orders = ref.watch(myOrdersProvider);

    return AsyncView(
      value: orders,
      loading: const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: CircularProgressIndicator()),
      ),
      onRetry: () => ref.invalidate(myOrdersProvider),
      builder: (list) {
        if (list.isEmpty) {
          return const Padding(
            padding: EdgeInsets.all(16),
            child: Text('No orders yet.'),
          );
        }

        return Column(
          children: [
            for (final order in list.take(5))
              ListTile(
                title: Text(order.orderNumber),
                subtitle: Text(
                  '${order.itemCount} item${order.itemCount == 1 ? '' : 's'}',
                ),
                trailing: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    MoneyText(order.totals.grandTotal),
                    Text(
                      humaniseStatus(order.status),
                      style: Theme.of(context).textTheme.labelSmall,
                    ),
                  ],
                ),
                onTap: () => context.push('/orders/${order.orderNumber}'),
              ),
          ],
        );
      },
    );
  }
}

class _LanguageTile extends ConsumerWidget {
  const _LanguageTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final locale = ref.watch(localeControllerProvider);

    return ListTile(
      leading: const Icon(Icons.translate),
      title: const Text('Language'),
      trailing: SegmentedButton<String>(
        segments: const [
          ButtonSegment(value: 'ar', label: Text('عربي')),
          ButtonSegment(value: 'en', label: Text('EN')),
        ],
        selected: {locale.languageCode},
        onSelectionChanged: (selection) =>
            ref.read(localeControllerProvider.notifier).setLocale(Locale(selection.first)),
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader(this.title);

  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsetsDirectional.only(start: 16, top: 12, bottom: 4),
      child: Text(title, style: Theme.of(context).textTheme.titleSmall),
    );
  }
}
