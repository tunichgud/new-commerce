package de.commerce.ingestion;

import de.commerce.elasticsearch.TvIndexService;
import de.commerce.elasticsearch.TvIndexService.BulkResult;
import de.commerce.model.TvProduct;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Orchestrates the full ingestion pipeline:
 * CSV parsing -> index setup -> bulk indexing -> summary logging.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    @Value("${ingestion.csv.path:data/electronics.csv}")
    private String defaultCsvPath;

    @Value("${ingestion.embeddings.enabled:true}")
    private boolean embeddingsEnabled;

    private final KaggleCsvParser csvParser;
    private final TvIndexService tvIndexService;
    private final EmbeddingModel embeddingModel;

    /**
     * Runs ingestion from the configured CSV path.
     *
     * @return BulkResult with counts of indexed and failed documents
     */
    public BulkResult runIngestion() throws IOException {
        return runIngestion(defaultCsvPath);
    }

    /**
     * Runs ingestion from the given CSV file path.
     *
     * @param csvPath path to the CSV file
     * @return BulkResult with counts of indexed and failed documents
     */
    public BulkResult runIngestion(String csvPath) throws IOException {
        log.info("Starting ingestion pipeline from '{}'", csvPath);

        List<TvProduct> products = csvParser.parse(csvPath);
        log.info("Parsed {} TV products from CSV.", products.size());

        tvIndexService.createIndexIfNotExists();

        if (products.isEmpty()) {
            log.warn("No TV products to index. Ingestion complete with 0 documents.");
            return new BulkResult(0, 0);
        }

        BulkResult result = embeddingsEnabled
                ? tvIndexService.indexBulkWithEmbeddings(products, embeddingModel)
                : tvIndexService.indexBulk(products);

        log.info("Ingestion summary: {} documents indexed successfully, {} failed.",
                result.indexed(), result.failed());

        return result;
    }
}
