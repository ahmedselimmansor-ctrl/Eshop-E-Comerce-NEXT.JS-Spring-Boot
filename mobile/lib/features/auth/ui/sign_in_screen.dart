import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/network/api_exception.dart';
import '../data/auth_repository.dart';

/// Sign in, or register, on one screen.
///
/// One screen rather than two because the fields barely differ and a customer who mistypes their
/// email on the sign-in tab should not have to navigate elsewhere and retype everything.
class SignInScreen extends ConsumerStatefulWidget {
  const SignInScreen({super.key, this.redirectTo});

  /// Where to go once signed in. Checkout sends customers here and wants them back.
  final String? redirectTo;

  @override
  ConsumerState<SignInScreen> createState() => _SignInScreenState();
}

class _SignInScreenState extends ConsumerState<SignInScreen> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _firstName = TextEditingController();
  final _phone = TextEditingController();

  bool _registering = false;
  bool _obscure = true;
  bool _busy = false;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    _firstName.dispose();
    _phone.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;

    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    final controller = ref.read(sessionControllerProvider.notifier);

    if (_registering) {
      await controller.register(
        email: _email.text.trim(),
        password: _password.text,
        firstName: _firstName.text.trim(),
        phone: _phone.text.trim(),
      );
    } else {
      await controller.login(
        emailOrPhone: _email.text.trim(),
        password: _password.text,
      );
    }

    if (!mounted) return;
    setState(() => _busy = false);

    final state = ref.read(sessionControllerProvider);
    if (state.hasError) {
      final error = state.error;
      messenger.showSnackBar(
        SnackBar(
          content: Text(error is ApiException ? error.detail : 'Could not sign you in'),
        ),
      );
      return;
    }

    if (state.valueOrNull != null && mounted) {
      context.go(widget.redirectTo ?? '/account');
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(_registering ? 'Create an account' : 'Sign in')),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (_registering) ...[
              TextFormField(
                controller: _firstName,
                textInputAction: TextInputAction.next,
                decoration: const InputDecoration(labelText: 'First name'),
                validator: (value) =>
                    (value == null || value.trim().isEmpty) ? 'Required' : null,
              ),
              const SizedBox(height: 12),
            ],

            TextFormField(
              controller: _email,
              keyboardType: TextInputType.emailAddress,
              textInputAction: TextInputAction.next,
              autofillHints: const [AutofillHints.email],
              decoration: InputDecoration(
                labelText: _registering ? 'Email' : 'Email or phone',
              ),
              validator: (value) {
                if (value == null || value.trim().isEmpty) return 'Required';
                if (_registering && !value.contains('@')) return 'Enter a valid email';
                return null;
              },
            ),
            const SizedBox(height: 12),

            if (_registering) ...[
              TextFormField(
                controller: _phone,
                keyboardType: TextInputType.phone,
                textInputAction: TextInputAction.next,
                decoration: const InputDecoration(
                  labelText: 'Phone (optional)',
                  hintText: '+20 100 000 0000',
                ),
              ),
              const SizedBox(height: 12),
            ],

            TextFormField(
              controller: _password,
              obscureText: _obscure,
              textInputAction: TextInputAction.done,
              autofillHints: const [AutofillHints.password],
              onFieldSubmitted: (_) => _submit(),
              decoration: InputDecoration(
                labelText: 'Password',
                suffixIcon: IconButton(
                  icon: Icon(_obscure ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                  onPressed: () => setState(() => _obscure = !_obscure),
                ),
              ),
              validator: (value) {
                if (value == null || value.isEmpty) return 'Required';
                // Mirrors the server's minimum so a rejected registration is caught here rather
                // than after a round trip.
                if (_registering && value.length < 8) return 'At least 8 characters';
                return null;
              },
            ),
            const SizedBox(height: 20),

            FilledButton(
              onPressed: _busy ? null : _submit,
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
              child: _busy
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : Text(_registering ? 'Create account' : 'Sign in'),
            ),
            const SizedBox(height: 8),

            TextButton(
              onPressed: _busy ? null : () => setState(() => _registering = !_registering),
              child: Text(
                _registering
                    ? 'Already have an account? Sign in'
                    : 'New here? Create an account',
              ),
            ),

            if (!_registering)
              TextButton(
                onPressed: _busy ? null : () => _forgotPassword(context),
                child: const Text('Forgot your password?'),
              ),

            const SizedBox(height: 24),
            Text(
              'Your password is never stored on this device. The session token lives in the '
              'platform keychain.',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _forgotPassword(BuildContext context) async {
    final email = _email.text.trim();
    final messenger = ScaffoldMessenger.of(context);

    if (email.isEmpty) {
      messenger.showSnackBar(
        const SnackBar(content: Text('Enter your email first, then tap this again')),
      );
      return;
    }

    await ref.read(authRepositoryProvider).requestPasswordReset(email);
    if (!mounted) return;

    // Deliberately the same message whether or not the address is registered: telling the caller
    // which emails exist is an account-enumeration hole.
    messenger.showSnackBar(
      const SnackBar(content: Text('If that address has an account, a reset link is on its way')),
    );
  }
}
