package de.commerce.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import de.commerce.elasticsearch.TvIndexService.BulkResult;
import de.commerce.ingestion.IngestionService;
import de.commerce.ingestion.KaggleCsvParser;
import de.commerce.ingestion.TvNameParser;
import de.commerce.model.TvProduct;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for TvIndexService against a real Elasticsearch instance
 * managed by Testcontainers.
 *
 * Tagged with "integration" so they can be run (or excluded) separately:
 *   mvn test -Dgroups=integration
 *   mvn test -DexcludedGroups=integration
 */
@Tag("integration")
@Testcontainers
class TvIndexServiceIntegrationTest {

    private static final String ES_IMAGE =
            "docker.elastic.co/elasticsearch/elasticsearch:8.12.0";

    @Container
    static final ElasticsearchContainer elasticsearchContainer =
            new ElasticsearchContainer(ES_IMAGE)
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("xpack.security.http.ssl.enabled", "false")
                    .withStartupTimeout(Duration.ofMinutes(3))
                    .waitingFor(
                            new HttpWaitStrategy()
                                    .forPort(9200)
                                    .forStatusCodeMatching(code -> code == 200 || code == 401)
                                    .withStartupTimeout(Duration.ofMinutes(3))
                    );

    private ElasticsearchClient esClient;
    private TvIndexService tvIndexService;

    @BeforeEach
    void setUp() {
        int port = elasticsearchContainer.getMappedPort(9200);
        String host = elasticsearchContainer.getHost();

        RestClient restClient = RestClient.builder(new HttpHost(host, port, "http")).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        esClient = new ElasticsearchClient(transport);

        tvIndexService = new TvIndexService(esClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Delete the index after each test to guarantee isolation
        boolean exists = esClient.indices()
                .exists(r -> r.index(TvIndexService.INDEX_NAME))
                .value();
        if (exists) {
            esClient.indices().delete(r -> r.index(TvIndexService.INDEX_NAME));
        }
    }

    // -------------------------------------------------------------------------
    // createIndexIfNotExists
    // -------------------------------------------------------------------------

    @Test
    void givenNoIndex_whenCreateIndexIfNotExists_thenIndexExistsAfterwards() throws IOException {
        tvIndexService.createIndexIfNotExists();

        boolean exists = esClient.indices()
                .exists(r -> r.index(TvIndexService.INDEX_NAME))
                .value();
        assertThat(exists).isTrue();
    }

    @Test
    void givenExistingIndex_whenCreateIndexIfNotExistsCalled2ndTime_thenNoErrorIsThrown()
            throws IOException {
        tvIndexService.createIndexIfNotExists();
        // Second call must be idempotent — must not throw
        tvIndexService.createIndexIfNotExists();

        boolean exists = esClient.indices()
                .exists(r -> r.index(TvIndexService.INDEX_NAME))
                .value();
        assertThat(exists).isTrue();
    }

    // -------------------------------------------------------------------------
    // indexBulk
    // -------------------------------------------------------------------------

    @Test
    void givenEmptyList_whenIndexBulk_thenReturnsZeroIndexedAndZeroFailed() throws IOException {
        tvIndexService.createIndexIfNotExists();

        BulkResult result = tvIndexService.indexBulk(List.of());

        assertThat(result.indexed()).isZero();
        assertThat(result.failed()).isZero();
    }

    @Test
    void given3Products_whenIndexBulk_thenReturns3IndexedAnd0Failed() throws IOException {
        tvIndexService.createIndexIfNotExists();
        List<TvProduct> products = List.of(
                buildTestProduct("a"),
                buildTestProduct("b"),
                buildTestProduct("c")
        );

        BulkResult result = tvIndexService.indexBulk(products);

        assertThat(result.indexed()).isEqualTo(3);
        assertThat(result.failed()).isZero();
    }

    @Test
    void given200Products_whenIndexBulk_thenAllAreIndexedAcrossBatches() throws IOException {
        tvIndexService.createIndexIfNotExists();
        List<TvProduct> products = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            products.add(buildTestProduct(String.valueOf(i)));
        }

        BulkResult result = tvIndexService.indexBulk(products);

        assertThat(result.indexed()).isEqualTo(200);
        assertThat(result.failed()).isZero();
    }

    // -------------------------------------------------------------------------
    // getCount
    // -------------------------------------------------------------------------

    @Test
    void given3IndexedProducts_whenGetCount_thenReturns3() throws IOException {
        tvIndexService.createIndexIfNotExists();
        List<TvProduct> products = List.of(
                buildTestProduct("x"),
                buildTestProduct("y"),
                buildTestProduct("z")
        );
        tvIndexService.indexBulk(products);

        // Force a refresh so the documents are immediately visible to count
        esClient.indices().refresh(r -> r.index(TvIndexService.INDEX_NAME));

        long count = tvIndexService.getCount();

        assertThat(count).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // Document retrieval
    // -------------------------------------------------------------------------

    @Test
    void givenIndexedProduct_whenGetByProductId_thenCorrectDocumentIsReturned() throws IOException {
        tvIndexService.createIndexIfNotExists();
        TvProduct product = buildTestProduct("retrieve-me");
        tvIndexService.indexBulk(List.of(product));

        // Force refresh before GET
        esClient.indices().refresh(r -> r.index(TvIndexService.INDEX_NAME));

        GetResponse<TvProduct> response = esClient.get(
                r -> r.index(TvIndexService.INDEX_NAME).id(product.productId()),
                TvProduct.class
        );

        assertThat(response.found()).isTrue();
        TvProduct retrieved = response.source();
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.productId()).isEqualTo(product.productId());
        assertThat(retrieved.brand()).isEqualTo(product.brand());
        assertThat(retrieved.screenSizeInch()).isEqualTo(product.screenSizeInch());
        assertThat(retrieved.panelType()).isEqualTo(product.panelType());
        assertThat(retrieved.resolution()).isEqualTo(product.resolution());
    }

    // -------------------------------------------------------------------------
    // AT-A006: Mapping validation — embedding field must be 768-dim cosine HNSW
    // -------------------------------------------------------------------------

    @Test
    void givenCreatedIndex_whenGetMapping_thenEmbeddingFieldIs768DimsWithCosineAndHnswIndex()
            throws IOException {
        tvIndexService.createIndexIfNotExists();

        var mapping = esClient.indices().getMapping(r -> r.index(TvIndexService.INDEX_NAME));
        var properties = mapping.result()
                .get(TvIndexService.INDEX_NAME)
                .mappings()
                .properties();

        assertThat(properties).containsKey("embedding");
        var denseVector = properties.get("embedding").denseVector();
        assertThat(denseVector.dims()).isEqualTo(768);
        assertThat(denseVector.similarity()).isEqualTo("cosine");
        assertThat(denseVector.index()).isTrue();
    }

    // -------------------------------------------------------------------------
    // AT-A008: indexBulkWithEmbeddings against real ES (mock EmbeddingModel)
    // -------------------------------------------------------------------------

    @Test
    void given3Products_whenIndexBulkWithEmbeddings_thenAllDocumentsStoredWithEmbeddingVector()
            throws IOException {
        tvIndexService.createIndexIfNotExists();

        // ES 8.12 rejects zero vectors for cosine similarity — use uniform non-zero vector
        float[] unitVector = new float[768];
        Arrays.fill(unitVector, 1.0f);
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        when(mockModel.embedAll(any())).thenAnswer(invocation -> {
            List<?> segments = invocation.getArgument(0);
            List<Embedding> embeddings = segments.stream()
                    .map(s -> Embedding.from(unitVector.clone()))
                    .toList();
            return Response.from(embeddings);
        });

        List<TvProduct> products = List.of(
                buildTestProduct("emb-a"),
                buildTestProduct("emb-b"),
                buildTestProduct("emb-c")
        );

        BulkResult result = tvIndexService.indexBulkWithEmbeddings(products, mockModel);

        assertThat(result.indexed()).isEqualTo(3);
        assertThat(result.failed()).isZero();

        esClient.indices().refresh(r -> r.index(TvIndexService.INDEX_NAME));
        assertThat(tvIndexService.getCount()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // AT-A009: Idempotency — indexing the same products twice does not duplicate
    // -------------------------------------------------------------------------

    @Test
    void givenSameProductsIndexedTwice_whenDoubleImport_thenDocumentCountStaysTheSame()
            throws IOException {
        tvIndexService.createIndexIfNotExists();
        List<TvProduct> products = List.of(
                buildTestProduct("dup-a"),
                buildTestProduct("dup-b"),
                buildTestProduct("dup-c")
        );

        tvIndexService.indexBulk(products);
        tvIndexService.indexBulk(products); // same IDs — ES must upsert

        esClient.indices().refresh(r -> r.index(TvIndexService.INDEX_NAME));
        assertThat(tvIndexService.getCount()).isEqualTo(3); // not 6
    }

    // -------------------------------------------------------------------------
    // AT-A010: End-to-end ingestion pipeline — CSV → parse → ES
    // -------------------------------------------------------------------------

    @TempDir
    Path tempDir;

    @Test
    void givenSampleCsvFile_whenEndToEndIngestion_thenTvProductsIndexedInElasticsearch()
            throws IOException {
        String csvPath = copyClasspathResourceToTempFile("test-data/sample_electronics.csv");

        TvNameParser nameParser = new TvNameParser();
        KaggleCsvParser csvParser = new KaggleCsvParser(nameParser);
        ReflectionTestUtils.setField(csvParser, "tvFilterEnabled", true);

        // Mock EmbeddingModel — returns non-zero 768-dim vectors (cosine requires non-zero)
        float[] unitVector = new float[768];
        Arrays.fill(unitVector, 1.0f);
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        when(mockModel.embedAll(any())).thenAnswer(invocation -> {
            List<?> segments = invocation.getArgument(0);
            List<Embedding> embeddings = segments.stream()
                    .map(s -> Embedding.from(unitVector.clone()))
                    .toList();
            return Response.from(embeddings);
        });

        IngestionService service = new IngestionService(csvParser, tvIndexService, mockModel);
        ReflectionTestUtils.setField(service, "defaultCsvPath", csvPath);
        ReflectionTestUtils.setField(service, "embeddingsEnabled", true);

        BulkResult result = service.runIngestion();

        // sample_electronics.csv contains 3 TV rows
        assertThat(result.indexed()).isEqualTo(3);
        assertThat(result.failed()).isZero();

        esClient.indices().refresh(r -> r.index(TvIndexService.INDEX_NAME));
        assertThat(tvIndexService.getCount()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // Helper — copy classpath resource to temp filesystem path
    // -------------------------------------------------------------------------

    private String copyClasspathResourceToTempFile(String resourcePath) throws IOException {
        URL url = getClass().getClassLoader().getResource(resourcePath);
        Objects.requireNonNull(url, "Classpath resource not found: " + resourcePath);
        Path target = tempDir.resolve(Path.of(resourcePath).getFileName().toString());
        try (InputStream in = url.openStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toAbsolutePath().toString();
    }

    // -------------------------------------------------------------------------
    // Helper — build test products
    // -------------------------------------------------------------------------

    /**
     * Builds a valid TvProduct for testing, using the given suffix to ensure
     * unique productIds across test cases.
     */
    private TvProduct buildTestProduct(String suffix) {
        String id = "test-product-" + suffix;
        return new TvProduct(
                id,
                "Samsung 55 inches 4K Smart OLED TV " + suffix,
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
                "https://img.example.com/tv-" + suffix + ".jpg",
                "https://amazon.in/dp/" + suffix,
                "Televisions",
                true,
                "Der Samsung 55 Zoll OLED Fernseher mit 4K UHD Auflösung. Smart TV. Preis: 879.89 EUR."
        );
    }
}
