package com.eshop.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.eshop.catalog.domain.Product;
import com.eshop.catalog.repository.ProductRepository;
import com.eshop.catalog.service.CategoryService;
import com.eshop.config.AppProperties;
import com.eshop.inventory.service.InventoryService;
import com.eshop.search.domain.ProductDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Builds and maintains the product index.
 *
 * <p>Indexing is driven by the outbox, not by the write path. Calling Elasticsearch inside the
 * transaction that saves a product would tie a catalog edit to the availability of a search cluster
 * and would index changes that later roll back. Instead the write records a
 * {@code product.updated} event, and the relay calls {@link #index} afterwards.
 *
 * <p>Every operation is idempotent - indexing the same product twice produces the same document -
 * so at-least-once delivery from the outbox is safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(ElasticsearchClient.class)
public class ProductIndexer {

    private static final int BULK_PAGE_SIZE = 200;
    /** The all-zero UUID sorts before every UUIDv7, so it seeds a full keyset scan. */
    private static final UUID SCAN_START = new UUID(0, 0);

    private final ElasticsearchClient client;
    private final ProductRepository products;
    private final CategoryService categories;
    private final InventoryService inventory;
    private final AppProperties properties;

    private String index() {
        return properties.getElasticsearch().getProductIndex();
    }

    /**
     * Creates the index with the mapping from {@code elasticsearch/product-index.json} if it does
     * not already exist.
     *
     * <p>Never modifies an existing index: most mapping changes are rejected by Elasticsearch
     * anyway, and the ones that are not can silently change analysis behaviour. Changing the
     * mapping means creating a new index and reindexing behind an alias.
     */
    public boolean createIndexIfMissing() {
        try {
            boolean exists = client.indices().exists(request -> request.index(index())).value();
            if (exists) {
                return false;
            }

            try (InputStream mapping = new ClassPathResource("elasticsearch/product-index.json")
                    .getInputStream()) {
                client.indices().create(request -> request.index(index()).withJson(mapping));
            }

            log.info("elasticsearch_index_created index={}", index());
            return true;

        } catch (Exception e) {
            log.error("elasticsearch_index_create_failed index={} error={}", index(), e.toString());
            return false;
        }
    }

    @Transactional(readOnly = true)
    public void index(UUID productId) {
        products.findDetailById(productId).ifPresentOrElse(
                this::indexProduct,
                // A product that no longer exists must not linger in search results.
                () -> delete(productId));
    }

    private void indexProduct(Product product) {
        // Draft and archived products are removed rather than indexed - leaving them with a status
        // filter would still let them surface if a query ever forgot the filter.
        if (!product.isPurchasable()) {
            delete(product.getId());
            return;
        }

        try {
            ProductDocument document = toDocument(product);
            client.index(request -> request
                    .index(index())
                    .id(product.getId().toString())
                    .document(document));

            log.debug("product_indexed id={} sku={}", product.getId(), product.getSku());

        } catch (Exception e) {
            log.error("product_index_failed id={} error={}", product.getId(), e.toString());
            // Rethrown so the outbox relay records the failure and retries rather than marking the
            // event published.
            throw new IllegalStateException("Failed to index product " + product.getId(), e);
        }
    }

    public void delete(UUID productId) {
        try {
            client.delete(request -> request.index(index()).id(productId.toString()));
            log.debug("product_removed_from_index id={}", productId);
        } catch (Exception e) {
            log.warn("product_index_delete_failed id={} error={}", productId, e.toString());
        }
    }

    /**
     * Full rebuild.
     *
     * <p>Pages by keyset ({@code id > lastSeen}) rather than by offset: a deep {@code OFFSET} scan
     * degrades quadratically, and a concurrent insert shifts the offsets so rows are skipped or
     * repeated. UUIDv7 keys sort chronologically, so the scan is a stable index walk.
     */
    @Transactional(readOnly = true)
    public long reindexAll() {
        createIndexIfMissing();

        UUID cursor = SCAN_START;
        long indexed = 0;

        while (true) {
            List<Product> page = products.findForIndexing(cursor, PageRequest.of(0, BULK_PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }

            indexed += bulkIndex(page);
            cursor = page.get(page.size() - 1).getId();
        }

        log.info("reindex_complete index={} documents={}", index(), indexed);
        return indexed;
    }

    private long bulkIndex(List<Product> page) {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        long queued = 0;

        for (Product product : page) {
            if (!product.isPurchasable()) {
                bulk.operations(operation -> operation.delete(delete -> delete
                        .index(index())
                        .id(product.getId().toString())));
                continue;
            }
            ProductDocument document = toDocument(product);
            bulk.operations(operation -> operation.index(indexOp -> indexOp
                    .index(index())
                    .id(product.getId().toString())
                    .document(document)));
            queued++;
        }

        try {
            BulkResponse response = client.bulk(bulk.build());
            if (response.errors()) {
                // Individual failures are logged rather than aborting the rebuild: one bad document
                // should not leave the whole index half-built.
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .limit(10)
                        .forEach(item -> log.error("bulk_index_item_failed id={} reason={}",
                                item.id(), item.error().reason()));
            }
        } catch (Exception e) {
            log.error("bulk_index_failed size={} error={}", page.size(), e.toString());
            throw new IllegalStateException("Bulk indexing failed", e);
        }

        return queued;
    }

    /** Resolves the denormalised fields a document needs: category ancestors and live stock. */
    private ProductDocument toDocument(Product product) {
        List<com.eshop.catalog.domain.Category> ancestors = product.getPrimaryCategory() == null
                ? List.of()
                : categories.ancestorsOf(product.getPrimaryCategory());

        int available = inventory.available(product.getId(), null);

        // A variable product's availability is the sum across its variants, not the product row's.
        if (!product.getVariants().isEmpty()) {
            available = product.getVariants().stream()
                    .mapToInt(variant -> inventory.available(product.getId(), variant.getId()))
                    .sum();
        }

        return ProductDocument.from(product, ancestors, available);
    }
}
