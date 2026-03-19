package de.commerce.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.BooleanProperty;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorIndexOptions;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.FloatNumberProperty;
import co.elastic.clients.elasticsearch._types.mapping.IntegerNumberProperty;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import de.commerce.model.TvProduct;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the "tv-products" Elasticsearch index: creation and bulk indexing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TvIndexService {

    public static final String INDEX_NAME = "tv-products";
    private static final int BULK_BATCH_SIZE = 100;

    private final ElasticsearchClient esClient;

    /**
     * Result record summarising a bulk indexing operation.
     */
    public record BulkResult(int indexed, int failed) {
    }

    public void createIndexIfNotExists() throws IOException {
        boolean exists = esClient.indices()
                .exists(ExistsRequest.of(r -> r.index(INDEX_NAME)))
                .value();

        if (exists) {
            log.info("Index '{}' already exists, skipping creation.", INDEX_NAME);
            return;
        }

        log.info("Creating index '{}'...", INDEX_NAME);
        CreateIndexResponse response = esClient.indices().create(r -> r
                .index(INDEX_NAME)
                .settings(s -> s
                        .numberOfShards("1")
                        .numberOfReplicas("0")
                )
                .mappings(m -> m
                        .properties(buildMappingProperties())
                )
        );

        log.info("Index '{}' created: acknowledged={}", INDEX_NAME, response.acknowledged());
    }

    public BulkResult indexBulk(List<TvProduct> products) throws IOException {
        if (products.isEmpty()) {
            log.info("No products to index.");
            return new BulkResult(0, 0);
        }

        int totalIndexed = 0;
        int totalFailed = 0;

        for (int i = 0; i < products.size(); i += BULK_BATCH_SIZE) {
            int end = Math.min(i + BULK_BATCH_SIZE, products.size());
            List<TvProduct> batch = products.subList(i, end);
            BulkResult batchResult = sendBatch(batch, i / BULK_BATCH_SIZE + 1);
            totalIndexed += batchResult.indexed();
            totalFailed += batchResult.failed();
        }

        log.info("Bulk indexing complete: {} indexed, {} failed out of {} total.",
                totalIndexed, totalFailed, products.size());
        return new BulkResult(totalIndexed, totalFailed);
    }

    public BulkResult indexBulkWithEmbeddings(List<TvProduct> products, EmbeddingModel embeddingModel)
            throws IOException {
        if (products.isEmpty()) {
            log.info("No products to index.");
            return new BulkResult(0, 0);
        }

        List<float[]> allEmbeddings = generateEmbeddings(products, embeddingModel);
        return sendBatchesWithEmbeddings(products, allEmbeddings);
    }

    private List<float[]> generateEmbeddings(List<TvProduct> products, EmbeddingModel embeddingModel) {
        List<String> texts = products.stream()
                .map(TvProduct::descriptionForEmbedding)
                .map(t -> t != null ? t : "")
                .toList();

        int batchSize = 20;
        List<float[]> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            List<TextSegment> segments = batch.stream()
                    .map(TextSegment::from)
                    .toList();
            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            response.content().stream()
                    .map(Embedding::vector)
                    .forEach(allEmbeddings::add);
            log.info("Embedded batch {}/{}", Math.min(i + batchSize, texts.size()), texts.size());
        }
        return allEmbeddings;
    }

    private BulkResult sendBatchesWithEmbeddings(List<TvProduct> products, List<float[]> embeddings)
            throws IOException {
        int totalIndexed = 0;
        int totalFailed = 0;

        for (int i = 0; i < products.size(); i += BULK_BATCH_SIZE) {
            int end = Math.min(i + BULK_BATCH_SIZE, products.size());
            List<TvProduct> batch = products.subList(i, end);
            List<float[]> batchEmbeddings = embeddings.subList(i, end);
            BulkResult batchResult = sendBatchWithEmbeddings(batch, batchEmbeddings, i / BULK_BATCH_SIZE + 1);
            totalIndexed += batchResult.indexed();
            totalFailed += batchResult.failed();
        }

        log.info("Bulk indexing with embeddings complete: {} indexed, {} failed out of {} total.",
                totalIndexed, totalFailed, products.size());
        return new BulkResult(totalIndexed, totalFailed);
    }

    private BulkResult sendBatchWithEmbeddings(List<TvProduct> batch, List<float[]> batchEmbeddings,
            int batchNumber) throws IOException {
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

        for (int i = 0; i < batch.size(); i++) {
            TvProduct product = batch.get(i);
            float[] embedding = batchEmbeddings.get(i);
            Map<String, Object> doc = buildDocumentWithEmbedding(product, embedding);
            String docId = product.productId();
            bulkBuilder.operations(op -> op
                    .index(idx -> idx
                            .index(INDEX_NAME)
                            .id(docId)
                            .document(doc)
                    )
            );
        }

        BulkResponse response = esClient.bulk(bulkBuilder.build());
        return countBulkResult(response, batch.size(), batchNumber);
    }

    private Map<String, Object> buildDocumentWithEmbedding(TvProduct product, float[] embedding) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("productId", product.productId());
        doc.put("name", product.name());
        doc.put("brand", product.brand());
        doc.put("screenSizeInch", product.screenSizeInch());
        doc.put("panelType", product.panelType());
        doc.put("resolution", product.resolution());
        doc.put("priceRaw", product.priceRaw());
        doc.put("priceNumeric", product.priceNumeric());
        doc.put("actualPriceRaw", product.actualPriceRaw());
        doc.put("actualPriceNumeric", product.actualPriceNumeric());
        doc.put("priceEur", product.priceEur());
        doc.put("actualPriceEur", product.actualPriceEur());
        doc.put("ratings", product.ratings());
        doc.put("noOfRatings", product.noOfRatings());
        doc.put("imageUrl", product.imageUrl());
        doc.put("productUrl", product.productUrl());
        doc.put("subCategory", product.subCategory());
        doc.put("isSmartTv", product.isSmartTv());
        doc.put("descriptionForEmbedding", product.descriptionForEmbedding());
        doc.put("embedding", embedding);
        return doc;
    }

    private BulkResult countBulkResult(BulkResponse response, int batchSize, int batchNumber) {
        int indexed = 0;
        int failed = 0;
        if (response.errors()) {
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    failed++;
                    log.warn("Batch {}: failed to index document id='{}': {}",
                            batchNumber, item.id(), item.error().reason());
                } else {
                    indexed++;
                }
            }
        } else {
            indexed = batchSize;
        }
        log.debug("Batch {}: {} indexed, {} failed.", batchNumber, indexed, failed);
        return new BulkResult(indexed, failed);
    }

    private BulkResult sendBatch(List<TvProduct> batch, int batchNumber) throws IOException {
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

        for (TvProduct product : batch) {
            String docId = product.productId();
            bulkBuilder.operations(op -> op
                    .index(idx -> idx
                            .index(INDEX_NAME)
                            .id(docId)
                            .document(product)
                    )
            );
        }

        BulkResponse response = esClient.bulk(bulkBuilder.build());
        return countBulkResult(response, batch.size(), batchNumber);
    }

    public long getCount() throws IOException {
        CountResponse response = esClient.count(c -> c.index(INDEX_NAME));
        return response.count();
    }

    private Map<String, Property> buildMappingProperties() {
        return Map.ofEntries(
                Map.entry("productId", Property.of(p -> p.keyword(KeywordProperty.of(k -> k)))),
                Map.entry("name", Property.of(p -> p.text(TextProperty.of(t -> t
                        .fields("raw", f -> f.keyword(KeywordProperty.of(k -> k))))))),
                Map.entry("brand", Property.of(p -> p.keyword(KeywordProperty.of(k -> k)))),
                Map.entry("screenSizeInch", Property.of(p -> p.integer(IntegerNumberProperty.of(i -> i)))),
                Map.entry("panelType", Property.of(p -> p.keyword(KeywordProperty.of(k -> k)))),
                Map.entry("resolution", Property.of(p -> p.keyword(KeywordProperty.of(k -> k)))),
                Map.entry("priceRaw", Property.of(p -> p.keyword(KeywordProperty.of(k -> k)))),
                Map.entry("priceNumeric", Property.of(p -> p.float_(FloatNumberProperty.of(f -> f)))),
                Map.entry("actualPriceRaw", Property.of(p -> p.keyword(KeywordProperty.of(k -> k)))),
                Map.entry("actualPriceNumeric", Property.of(p -> p.float_(FloatNumberProperty.of(f -> f)))),
                Map.entry("priceEur", Property.of(p -> p.float_(FloatNumberProperty.of(f -> f)))),
                Map.entry("actualPriceEur", Property.of(p -> p.float_(FloatNumberProperty.of(f -> f)))),
                Map.entry("ratings", Property.of(p -> p.float_(FloatNumberProperty.of(f -> f)))),
                Map.entry("noOfRatings", Property.of(p -> p.integer(IntegerNumberProperty.of(i -> i)))),
                Map.entry("imageUrl", Property.of(p -> p.keyword(KeywordProperty.of(k -> k.index(false))))),
                Map.entry("productUrl", Property.of(p -> p.keyword(KeywordProperty.of(k -> k.index(false))))),
                Map.entry("subCategory", Property.of(p -> p.keyword(KeywordProperty.of(k -> k)))),
                Map.entry("isSmartTv", Property.of(p -> p.boolean_(BooleanProperty.of(b -> b)))),
                Map.entry("descriptionForEmbedding", Property.of(p -> p.text(TextProperty.of(t -> t)))),
                Map.entry("embedding", Property.of(p -> p.denseVector(DenseVectorProperty.of(d -> d
                        .dims(768)
                        .index(true)
                        .similarity("cosine")
                        .indexOptions(DenseVectorIndexOptions.of(o -> o
                                .type("hnsw")
                                .m(16)
                                .efConstruction(100)
                        ))
                ))))
        );
    }
}
