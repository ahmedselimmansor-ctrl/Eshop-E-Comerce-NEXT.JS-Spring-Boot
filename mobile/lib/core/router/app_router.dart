import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/account/ui/account_screen.dart';
import '../../features/account/ui/addresses_screen.dart';
import '../../features/account/ui/order_detail_screen.dart';
import '../../features/auth/ui/sign_in_screen.dart';
import '../../features/cart/ui/cart_screen.dart';
import '../../features/catalog/ui/category_screen.dart';
import '../../features/catalog/ui/home_screen.dart';
import '../../features/catalog/ui/product_screen.dart';
import '../../features/catalog/ui/search_screen.dart';
import '../../features/checkout/ui/checkout_screen.dart';

/// Application routes.
///
/// Paths mirror the web storefront's so a link shared from either lands in the same place, and so a
/// push notification carrying `orderNumber` can deep-link straight to that order.
///
/// Nothing is guarded here. Checkout and the account screen decide for themselves what to show a
/// signed-out visitor, because a redirect would throw away the route they were heading for, and a
/// customer bounced to a sign-in screen with no way back is worse than one shown a sign-in prompt in
/// place of the content.
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
            builder: (context, state) => ProductScreen(slug: state.pathParameters['slug']!),
          ),
          GoRoute(
            path: 'categories/:slug',
            builder: (context, state) => CategoryScreen(slug: state.pathParameters['slug']!),
          ),
          GoRoute(
            path: 'search',
            builder: (context, state) =>
                SearchScreen(initialQuery: state.uri.queryParameters['q']),
          ),
          GoRoute(path: 'cart', builder: (context, state) => const CartScreen()),
          GoRoute(path: 'checkout', builder: (context, state) => const CheckoutScreen()),

          GoRoute(
            path: 'sign-in',
            builder: (context, state) =>
                SignInScreen(redirectTo: state.uri.queryParameters['redirect']),
          ),

          GoRoute(
            path: 'orders/:orderNumber',
            builder: (context, state) =>
                OrderDetailScreen(orderNumber: state.pathParameters['orderNumber']!),
          ),

          GoRoute(
            path: 'account',
            builder: (context, state) => const AccountScreen(),
            routes: [
              GoRoute(
                path: 'addresses',
                builder: (context, state) => const AddressesScreen(),
              ),
            ],
          ),
        ],
      ),
    ],

    // A router error should not be a white screen with a stack trace.
    errorBuilder: (context, state) => Scaffold(
      appBar: AppBar(),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.explore_off_outlined, size: 44),
              const SizedBox(height: 12),
              const Text('That page does not exist'),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: () => context.go('/'),
                child: const Text('Go to the store'),
              ),
            ],
          ),
        ),
      ),
    ),
  );
});
