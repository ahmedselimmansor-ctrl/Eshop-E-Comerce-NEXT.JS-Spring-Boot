import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/cart/ui/cart_screen.dart';
import '../../features/catalog/ui/home_screen.dart';
import '../../features/catalog/ui/product_screen.dart';

/// Application routes.
///
/// Paths mirror the web storefront's so a link shared from either lands in the same place, and so a
/// push notification carrying `orderNumber` can deep-link straight to that order.
final appRouterProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(
        path: '/',
        builder: (context, state) => const HomeScreen(),
        routes: [
          GoRoute(
            path: 'products/:slug',
            builder: (context, state) =>
                ProductScreen(slug: state.pathParameters['slug']!),
          ),
          GoRoute(path: 'cart', builder: (context, state) => const CartScreen()),

          // Not built yet. Named here rather than omitted so the links that point at them resolve
          // to an honest "not finished" screen instead of the router's error page, which reads as a
          // crash to anyone using the app.
          GoRoute(
            path: 'search',
            builder: (context, state) => const _NotBuiltYet('Search'),
          ),
          GoRoute(
            path: 'categories/:slug',
            builder: (context, state) =>
                _NotBuiltYet('Category · ${state.pathParameters['slug']}'),
          ),
          GoRoute(
            path: 'checkout',
            builder: (context, state) => const _NotBuiltYet('Checkout'),
          ),
          GoRoute(
            path: 'orders/:orderNumber',
            builder: (context, state) =>
                _NotBuiltYet('Order ${state.pathParameters['orderNumber']}'),
          ),
          GoRoute(
            path: 'account',
            builder: (context, state) => const _NotBuiltYet('Account'),
          ),
        ],
      ),
    ],

    // A router error should not be a white screen with a stack trace.
    errorBuilder: (context, state) => Scaffold(
      appBar: AppBar(),
      body: Center(child: Text(state.error?.toString() ?? 'Not found')),
    ),
  );
});

/// A screen that exists as a route but has no implementation yet.
class _NotBuiltYet extends StatelessWidget {
  const _NotBuiltYet(this.label);

  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(label)),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.construction_outlined, size: 44, color: theme.colorScheme.outline),
              const SizedBox(height: 12),
              Text('$label is not built yet', style: theme.textTheme.titleMedium),
              const SizedBox(height: 6),
              Text(
                'This route is wired up so links resolve, but the screen itself is still to come.',
                textAlign: TextAlign.center,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
