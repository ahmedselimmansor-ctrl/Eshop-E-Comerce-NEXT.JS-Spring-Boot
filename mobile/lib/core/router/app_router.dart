import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

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
        builder: (context, state) => const _Placeholder('Home'),
        routes: [
          GoRoute(
            path: 'products/:slug',
            builder: (context, state) =>
                _Placeholder('Product ${state.pathParameters['slug']}'),
          ),
          GoRoute(path: 'cart', builder: (context, state) => const _Placeholder('Cart')),
          GoRoute(path: 'checkout', builder: (context, state) => const _Placeholder('Checkout')),
          GoRoute(
            path: 'orders/:orderNumber',
            builder: (context, state) =>
                _Placeholder('Order ${state.pathParameters['orderNumber']}'),
          ),
          GoRoute(path: 'account', builder: (context, state) => const _Placeholder('Account')),
        ],
      ),
    ],

    // A router error should not be a white screen with a stack trace.
    errorBuilder: (context, state) => Scaffold(
      body: Center(child: Text(state.error?.toString() ?? 'Not found')),
    ),
  );
});

class _Placeholder extends StatelessWidget {
  const _Placeholder(this.label);

  final String label;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(label)),
      body: Center(child: Text(label)),
    );
  }
}
