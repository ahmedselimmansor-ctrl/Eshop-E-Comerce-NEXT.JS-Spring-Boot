import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/widgets/async_view.dart';
import '../data/catalog_repository.dart';
import 'product_grid.dart';

/// Search.
class SearchScreen extends ConsumerStatefulWidget {
  const SearchScreen({super.key, this.initialQuery});

  final String? initialQuery;

  @override
  ConsumerState<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends ConsumerState<SearchScreen> {
  late final TextEditingController _controller =
      TextEditingController(text: widget.initialQuery ?? '');

  /// The query actually sent, which lags what is typed by [_debounceDelay].
  String _submitted = '';
  Timer? _debounce;
  String _sort = 'RELEVANCE';

  static const _debounceDelay = Duration(milliseconds: 350);

  @override
  void initState() {
    super.initState();
    _submitted = widget.initialQuery ?? '';
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  /// Debounced so typing "phone" issues one request rather than five.
  void _onChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(_debounceDelay, () {
      if (mounted) setState(() => _submitted = value.trim());
    });
  }

  @override
  Widget build(BuildContext context) {
    final query = ProductQuery(search: _submitted.isEmpty ? null : _submitted, sort: _sort);
    final results = ref.watch(productListProvider(query));

    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _controller,
          autofocus: widget.initialQuery == null,
          textInputAction: TextInputAction.search,
          onChanged: _onChanged,
          onSubmitted: (value) {
            _debounce?.cancel();
            setState(() => _submitted = value.trim());
          },
          decoration: InputDecoration(
            hintText: 'Search products',
            border: InputBorder.none,
            suffixIcon: _controller.text.isEmpty
                ? null
                : IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: () {
                      _controller.clear();
                      setState(() => _submitted = '');
                    },
                  ),
          ),
        ),
      ),
      body: Column(
        children: [
          _SortBar(sort: _sort, onChanged: (value) => setState(() => _sort = value)),
          Expanded(
            child: AsyncView(
              value: results,
              onRetry: () => ref.invalidate(productListProvider(query)),
              builder: (page) => page.content.isEmpty
                  ? EmptyState(
                      icon: Icons.search_off,
                      title: _submitted.isEmpty
                          ? 'Search the store'
                          : 'Nothing matched "$_submitted"',
                      hint: _submitted.isEmpty
                          ? 'Type a product name, brand, or SKU.'
                          : 'Try a shorter or more general term.',
                    )
                  : ProductGrid(products: page.content),
            ),
          ),
        ],
      ),
    );
  }
}

class _SortBar extends StatelessWidget {
  const _SortBar({required this.sort, required this.onChanged});

  final String sort;
  final ValueChanged<String> onChanged;

  static const _options = {
    'RELEVANCE': 'Relevance',
    'PRICE_ASC': 'Price, low to high',
    'PRICE_DESC': 'Price, high to low',
    'NEWEST': 'Newest',
    'RATING': 'Rating',
  };

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 48,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsetsDirectional.only(start: 12, end: 12),
        children: [
          for (final entry in _options.entries)
            Padding(
              padding: const EdgeInsetsDirectional.only(end: 8),
              child: Center(
                child: ChoiceChip(
                  label: Text(entry.value),
                  selected: sort == entry.key,
                  onSelected: (_) => onChanged(entry.key),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
