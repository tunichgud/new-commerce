package de.commerce.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import de.commerce.model.SearchFilters;
import de.commerce.model.ScoredProduct;
import de.commerce.model.SortField;
import de.commerce.model.TvProduct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provides BM25 and hybrid (kNN + BM25 with RRF) search over the tv-products index.
 * When sort fields are present in filters, BM25-only search is used (RRF + sort are mutually exclusive).
 */
@Slf4j
@Service
public class ElasticsearchSearchService {

    private static final String FIELD_NAME = "name";
    private static final String FIELD_DESCRIPTION = "descriptionForEmbedding";
    private static final String FIELD_BRAND = "brand";
    private static final String FIELD_SCREEN_SIZE = "screenSizeInch";
    private static final String FIELD_PANEL_TYPE = "panelType";
    private static final String FIELD_PRICE_EUR = "priceEur";
    private static final String FIELD_RATINGS = "ratings";
    private static final String FIELD_EMBEDDING = "embedding";
    private static final String FIELD_RESOLUTION = "resolution";

    /** Accessories in the dataset have resolution="Unknown"; real TVs always have a proper value. */
    private static final String RESOLUTION_UNKNOWN = "Unknown";

    private final ElasticsearchClient esClient;
    private final int rrfRankConstant;
    private final int rrfWindowSize;

    public ElasticsearchSearchService(
            ElasticsearchClient esClient,
            @Value("${search.rrf.rank-constant:60}") int rrfRankConstant,
            @Value("${search.rrf.window-size:100}") int rrfWindowSize) {
        this.esClient = esClient;
        this.rrfRankConstant = rrfRankConstant;
        this.rrfWindowSize = rrfWindowSize;
    }

    /**
     * BM25 multi-match search with optional filter clauses and optional sort derived from
     * {@link SearchFilters}. Sort fields are applied if present; otherwise results are
     * ranked by BM25 score.
     *
     * @param query   free-text query string
     * @param filters optional filters (null fields are ignored); sortFields are applied if present
     * @param topK    maximum number of results to return
     * @return ranked list of scored products
     */
    public List<ScoredProduct> searchBm25(String query, SearchFilters filters, int topK)
            throws IOException {
        log.debug("BM25 search: query='{}', topK={}, filters={}", query, topK, filters);

        List<Query> filterClauses = buildFilterClauses(filters);

        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(TvIndexService.INDEX_NAME)
                .size(topK)
                .query(q -> q.bool(buildBoolQuery(query, filterClauses)));

        applySortFields(builder, filters);

        SearchResponse<Map> response = esClient.search(builder.build(), Map.class);
        List<ScoredProduct> results = hitsToScoredProducts(response);
        log.debug("BM25 search returned {} results.", results.size());
        return results;
    }

    /**
     * Hybrid search combining kNN vector search and BM25 text search via
     * Reciprocal Rank Fusion (RRF).
     * <p>
     * When {@code filters.sortFields()} is non-empty, RRF cannot be used together with
     * explicit sort — this method delegates to {@link #searchBm25} in that case.
     *
     * @param query           free-text query string
     * @param embeddingVector dense vector for the kNN portion
     * @param filters         optional filters applied to both kNN and BM25
     * @param topK            maximum number of results to return
     * @return ranked list of scored products
     */
    public List<ScoredProduct> searchHybrid(
            String query,
            float[] embeddingVector,
            SearchFilters filters,
            int topK) throws IOException {

        if (hasSortFields(filters)) {
            log.debug("Sort fields present — falling back to BM25 (RRF incompatible with sort).");
            return searchBm25(query, filters, topK);
        }

        log.debug("Hybrid search: query='{}', topK={}, windowSize={}, rankConstant={}",
                query, topK, rrfWindowSize, rrfRankConstant);

        List<Query> filterClauses = buildFilterClauses(filters);
        List<Float> vectorAsList = toFloatList(embeddingVector);

        SearchRequest request = SearchRequest.of(r -> r
                .index(TvIndexService.INDEX_NAME)
                .size(topK)
                .query(q -> q
                        .bool(buildBoolQuery(query, filterClauses))
                )
                .knn(buildKnnQuery(vectorAsList, filterClauses, topK))
                .rank(rank -> rank
                        .rrf(rrf -> rrf
                                .rankConstant((long) rrfRankConstant)
                                .windowSize((long) rrfWindowSize)
                        )
                )
        );

        SearchResponse<Map> response = esClient.search(request, Map.class);
        List<ScoredProduct> results = hitsToScoredProducts(response);
        log.debug("Hybrid search returned {} results.", results.size());
        return results;
    }

    // --- private helpers ---

    private boolean hasSortFields(SearchFilters filters) {
        return filters != null
                && filters.sortFields() != null
                && !filters.sortFields().isEmpty();
    }

    private void applySortFields(SearchRequest.Builder builder, SearchFilters filters) {
        if (!hasSortFields(filters)) {
            return;
        }
        for (SortField sf : filters.sortFields()) {
            SortOrder order = "asc".equalsIgnoreCase(sf.order()) ? SortOrder.Asc : SortOrder.Desc;
            builder.sort(s -> s.field(f -> f.field(sf.field()).order(order)));
        }
    }

    private BoolQuery buildBoolQuery(String query, List<Query> filterClauses) {
        return BoolQuery.of(b -> {
            b.must(m -> m
                    .multiMatch(mm -> mm
                            .query(query)
                            .fields(FIELD_NAME, FIELD_DESCRIPTION)
                    )
            );
            // Always exclude accessories (wall mounts, remotes, stabilizers) which have resolution=Unknown
            b.mustNot(mn -> mn
                    .term(t -> t.field(FIELD_RESOLUTION).value(RESOLUTION_UNKNOWN))
            );
            filterClauses.forEach(b::filter);
            return b;
        });
    }

    private KnnQuery buildKnnQuery(List<Float> vector, List<Query> filterClauses, int topK) {
        // Exclude accessories for kNN as well (resolution=Unknown means accessory, not a TV)
        Query excludeUnknownResolution = Query.of(q -> q.bool(b -> b
                .mustNot(mn -> mn.term(t -> t.field(FIELD_RESOLUTION).value(RESOLUTION_UNKNOWN)))
        ));
        return KnnQuery.of(k -> {
            k.field(FIELD_EMBEDDING)
                    .queryVector(vector)
                    .k(topK)
                    .numCandidates(rrfWindowSize);
            k.filter(excludeUnknownResolution);
            filterClauses.forEach(k::filter);
            return k;
        });
    }

    private List<Query> buildFilterClauses(SearchFilters filters) {
        List<Query> clauses = new ArrayList<>();
        if (filters == null) {
            return clauses;
        }
        if (filters.brand() != null) {
            clauses.add(Query.of(q -> q.term(t -> t.field(FIELD_BRAND).value(filters.brand()))));
        }
        if (filters.screenSizeInch() != null) {
            clauses.add(Query.of(q -> q.term(t -> t
                    .field(FIELD_SCREEN_SIZE)
                    .value(filters.screenSizeInch()))));
        }
        if (filters.panelType() != null) {
            clauses.add(Query.of(q -> q.term(t -> t.field(FIELD_PANEL_TYPE).value(filters.panelType()))));
        }
        if (filters.maxPriceEur() != null) {
            clauses.add(Query.of(q -> q.range(r -> r
                    .field(FIELD_PRICE_EUR)
                    .lte(co.elastic.clients.json.JsonData.of(filters.maxPriceEur())))));
        }
        if (filters.minRating() != null) {
            clauses.add(Query.of(q -> q.range(r -> r
                    .field(FIELD_RATINGS)
                    .gte(co.elastic.clients.json.JsonData.of(filters.minRating())))));
        }
        return clauses;
    }

    @SuppressWarnings("unchecked")
    public List<ScoredProduct> hitsToScoredProducts(SearchResponse<Map> response) {
        List<ScoredProduct> results = new ArrayList<>();
        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> source = hit.source();
            if (source == null) {
                log.warn("Hit id='{}' has no source document; skipping.", hit.id());
                continue;
            }
            double score = hit.score() != null ? hit.score() : 0.0;
            TvProduct product = mapToTvProduct(source, hit.id());
            results.add(new ScoredProduct(product, score));
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    public TvProduct mapToTvProduct(Map<String, Object> source, String hitId) {
        // productId from source; fall back to Elasticsearch document _id
        String productId = source.get("productId") != null
                ? source.get("productId").toString()
                : hitId;
        return new TvProduct(
                productId,
                asString(source.get("name")),
                asString(source.get("brand")),
                asInteger(source.get("screenSizeInch")),
                asString(source.get("panelType")),
                asString(source.get("resolution")),
                asString(source.get("priceRaw")),
                asDouble(source.get("priceNumeric")),
                asString(source.get("actualPriceRaw")),
                asDouble(source.get("actualPriceNumeric")),
                asDouble(source.get("priceEur")),
                asDouble(source.get("actualPriceEur")),
                asDouble(source.get("ratings")),
                asInteger(source.get("noOfRatings")),
                asString(source.get("imageUrl")),
                asString(source.get("productUrl")),
                asString(source.get("subCategory")),
                asBoolean(source.get("isSmartTv")),
                asString(source.get("descriptionForEmbedding"))
        );
    }

    private List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float v : array) {
            list.add(v);
        }
        return list;
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Double d) return d;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean asBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString());
    }
}
