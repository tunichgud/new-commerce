package de.commerce.ingestion;

import de.commerce.elasticsearch.TvIndexService;
import de.commerce.elasticsearch.TvIndexService.BulkResult;
import de.commerce.model.TvProduct;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IngestionService.
 * All collaborators are mocked — no Spring context, no Elasticsearch, no file I/O.
 *
 * AT-A001: Happy path — CSV parsed, index created, products indexed
 * AT-A002: Empty CSV — index created, indexBulk never called
 * AT-A003: Embeddings enabled — indexBulkWithEmbeddings is called
 * AT-A004: Embeddings disabled — indexBulk (no vectors) is called
 * AT-A005: IOException from CSV parser propagates to caller
 */
@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private KaggleCsvParser csvParser;
    @Mock
    private TvIndexService tvIndexService;
    @Mock
    private EmbeddingModel embeddingModel;

    private IngestionService ingestionService;

    private static final String TEST_CSV_PATH = "/test/path/products.csv";

    @BeforeEach
    void setUp() {
        ingestionService = new IngestionService(csvParser, tvIndexService, embeddingModel);
        ReflectionTestUtils.setField(ingestionService, "defaultCsvPath", TEST_CSV_PATH);
        ReflectionTestUtils.setField(ingestionService, "embeddingsEnabled", false);
    }

    // -------------------------------------------------------------------------
    // AT-A001: Happy path
    // -------------------------------------------------------------------------

    @Test
    void givenSuccessfulCsvParse_whenRunIngestion_thenCreatesIndexAndReturnsBulkResult()
            throws IOException {
        List<TvProduct> products = List.of(buildProduct("a"), buildProduct("b"), buildProduct("c"));
        when(csvParser.parse(TEST_CSV_PATH)).thenReturn(products);
        when(tvIndexService.indexBulk(products)).thenReturn(new BulkResult(3, 0));

        BulkResult result = ingestionService.runIngestion();

        assertThat(result.indexed()).isEqualTo(3);
        assertThat(result.failed()).isZero();
        verify(tvIndexService).createIndexIfNotExists();
        verify(tvIndexService).indexBulk(products);
    }

    // -------------------------------------------------------------------------
    // AT-A002: Empty CSV
    // -------------------------------------------------------------------------

    @Test
    void givenEmptyCsv_whenRunIngestion_thenCreatesIndexButSkipsIndexBulk()
            throws IOException {
        when(csvParser.parse(TEST_CSV_PATH)).thenReturn(List.of());

        BulkResult result = ingestionService.runIngestion();

        assertThat(result.indexed()).isZero();
        assertThat(result.failed()).isZero();
        verify(tvIndexService).createIndexIfNotExists();
        verify(tvIndexService, never()).indexBulk(any());
        verify(tvIndexService, never()).indexBulkWithEmbeddings(any(), any());
    }

    // -------------------------------------------------------------------------
    // AT-A003: Embeddings enabled → indexBulkWithEmbeddings is called
    // -------------------------------------------------------------------------

    @Test
    void givenEmbeddingsEnabled_whenRunIngestion_thenCallsIndexBulkWithEmbeddings()
            throws IOException {
        ReflectionTestUtils.setField(ingestionService, "embeddingsEnabled", true);
        List<TvProduct> products = List.of(buildProduct("x"));
        when(csvParser.parse(TEST_CSV_PATH)).thenReturn(products);
        when(tvIndexService.indexBulkWithEmbeddings(products, embeddingModel))
                .thenReturn(new BulkResult(1, 0));

        ingestionService.runIngestion();

        verify(tvIndexService).indexBulkWithEmbeddings(products, embeddingModel);
        verify(tvIndexService, never()).indexBulk(any());
    }

    // -------------------------------------------------------------------------
    // AT-A004: Embeddings disabled → plain indexBulk is called
    // -------------------------------------------------------------------------

    @Test
    void givenEmbeddingsDisabled_whenRunIngestion_thenCallsIndexBulkWithoutEmbeddings()
            throws IOException {
        List<TvProduct> products = List.of(buildProduct("y"));
        when(csvParser.parse(TEST_CSV_PATH)).thenReturn(products);
        when(tvIndexService.indexBulk(products)).thenReturn(new BulkResult(1, 0));

        ingestionService.runIngestion();

        verify(tvIndexService).indexBulk(products);
        verify(tvIndexService, never()).indexBulkWithEmbeddings(any(), any());
    }

    // -------------------------------------------------------------------------
    // AT-A005: IOException from CSV parser propagates
    // -------------------------------------------------------------------------

    @Test
    void givenCsvParserThrowsIOException_whenRunIngestion_thenExceptionPropagates()
            throws IOException {
        when(csvParser.parse(TEST_CSV_PATH)).thenThrow(new IOException("CSV file not found"));

        assertThatThrownBy(() -> ingestionService.runIngestion())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CSV file not found");
        verify(tvIndexService, never()).indexBulk(any());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private TvProduct buildProduct(String suffix) {
        return new TvProduct(
                "id-" + suffix,
                "Samsung 55 inches 4K TV " + suffix,
                "Samsung",
                55,
                "OLED",
                "4K UHD",
                "₹79990",
                79990.0,
                "₹89990",
                89990.0,
                879.89,
                989.89,
                4.3,
                1500,
                "https://img.example.com/tv.jpg",
                "https://amazon.in/dp/" + suffix,
                "Televisions",
                true,
                "Beschreibung TV " + suffix
        );
    }
}
