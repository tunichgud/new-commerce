package de.commerce.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import de.commerce.model.ScoredProduct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Performs a diversity search across three price segments, returning a balanced
 * selection of products covering low, mid, and premium price ranges.
 * At most one product per brand per segment is returned (deduplication).
 */
@Slf4j
@Service
public class DiversitySearchService {

    private static final String FIELD_PRICE_EUR = "priceEur";
    private static final String FIELD_RATINGS = "ratings";
    private static final String FIELD_BRAND = "brand";

    private final ElasticsearchClient esClient;
    private final ElasticsearchSearchService searchService;
    private final double segment1Max;
    private final double segment2Max;
    private final int productsPerSegment;

    public DiversitySearchService(
            ElasticsearchClient esClient,
            ElasticsearchSearchService searchService,
            @Value("${search.diversity.segment1-max:250.0}") double segment1Max,
            @Value("${search.diversity.segment2-max:600.0}") double segment2Max,
            @Value("${search.diversity.products-per-segment:3}") int productsPerSegment) {
        this.esClient = esClient;
        this.searchService = searchService;
        this.segment1Max = segment1Max;
        this.segment2Max = segment2Max;
        this.productsPerSegment = productsPerSegment;
    }

    /**
     * Runs three separate price-range queries and returns a combined, deduplicated
     * list of products covering all price segments.
     *
     * @return flat list of scored products (score=0 for all)
     */
    public List<ScoredProduct> searchDiverse() throws IOException {
        List<ScoredProduct> result = new ArrayList<>();
        result.addAll(searchSegment(0.0, segment1Max));
        result.addAll(searchSegment(segment1Max, segment2Max));
        result.addAll(searchSegment(segment2Max, Double.MAX_VALUE));
        log.debug("Diversity search returned {} total products across 3 segments.", result.size());
        return result;
    }

    private List<ScoredProduct> searchSegment(double minPrice, double maxPrice) throws IOException {
        int candidateSize = productsPerSegment * 2;

        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(TvIndexService.INDEX_NAME)
                .size(candidateSize)
                .query(q -> q.bool(b -> {
                    b.filter(buildPriceRangeQuery(minPrice, maxPrice));
                    return b;
                }))
                .sort(s -> s.field(f -> f.field(FIELD_RATINGS).order(SortOrder.Desc)));

        SearchResponse<Map> response = esClient.search(builder.build(), Map.class);
        List<ScoredProduct> candidates = searchService.hitsToScoredProducts(response);

        return deduplicateByBrand(candidates);
    }

    /**
     * Keeps at most one product per brand, in original order (first occurrence wins).
     * Returns at most {@link #productsPerSegment} products.
     */
    private List<ScoredProduct> deduplicateByBrand(List<ScoredProduct> candidates) {
        Map<String, ScoredProduct> byBrand = new LinkedHashMap<>();
        for (ScoredProduct sp : candidates) {
            String brand = sp.product().brand();
            String key = brand != null ? brand.toLowerCase() : "__unknown__";
            byBrand.putIfAbsent(key, sp);
            if (byBrand.size() >= productsPerSegment) {
                break;
            }
        }
        return new ArrayList<>(byBrand.values());
    }

    private Query buildPriceRangeQuery(double minPrice, double maxPrice) {
        return Query.of(q -> q.range(r -> {
            r.field(FIELD_PRICE_EUR);
            if (minPrice > 0) {
                r.gte(co.elastic.clients.json.JsonData.of(minPrice));
            }
            if (maxPrice < Double.MAX_VALUE) {
                r.lte(co.elastic.clients.json.JsonData.of(maxPrice));
            }
            return r;
        }));
    }
}
