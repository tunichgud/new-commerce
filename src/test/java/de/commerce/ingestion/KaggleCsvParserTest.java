package de.commerce.ingestion;

import de.commerce.model.TvProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for KaggleCsvParser.
 * Uses real CSV test files from the classpath; no Spring context required.
 * Because KaggleCsvParser.parse() accepts a filesystem path, classpath resources
 * are copied to a temporary directory for each test.
 */
class KaggleCsvParserTest {

    private KaggleCsvParser parser;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        TvNameParser tvNameParser = new TvNameParser();
        parser = new KaggleCsvParser(tvNameParser);
        // tvFilterEnabled defaults to true via @Value; override for tests that need false
        ReflectionTestUtils.setField(parser, "tvFilterEnabled", true);
        ReflectionTestUtils.setField(parser, "inrToEurRate", 0.011);
        tempDir = Files.createTempDirectory("kaggle-csv-test-");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Copies a classpath resource to a temp file and returns the absolute path string.
     */
    private String classpathResourceToTempFile(String resourcePath) throws IOException {
        URL url = getClass().getClassLoader().getResource(resourcePath);
        Objects.requireNonNull(url, "Classpath resource not found: " + resourcePath);
        Path target = tempDir.resolve(Path.of(resourcePath).getFileName().toString());
        try (InputStream in = url.openStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toAbsolutePath().toString();
    }

    /**
     * Creates a UTF-8 BOM temp file from the given classpath resource,
     * prepending the BOM bytes to the content.
     */
    private String createBomCsvTempFile(String resourcePath) throws IOException {
        URL url = getClass().getClassLoader().getResource(resourcePath);
        Objects.requireNonNull(url, "Classpath resource not found: " + resourcePath);
        byte[] originalBytes;
        try (InputStream in = url.openStream()) {
            originalBytes = in.readAllBytes();
        }
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] withBom = new byte[bom.length + originalBytes.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(originalBytes, 0, withBom, bom.length, originalBytes.length);
        Path target = tempDir.resolve("bom_sample_electronics.csv");
        Files.write(target, withBom);
        return target.toAbsolutePath().toString();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void givenSampleCsvWithFilterEnabled_whenParse_thenReturnsOnlyTvProducts() throws IOException {
        String csvPath = classpathResourceToTempFile("test-data/sample_electronics.csv");

        List<TvProduct> products = parser.parse(csvPath);

        // sample has 3 TV rows (name contains " TV" + size), 1 Laptop, 1 Headphone
        assertThat(products).hasSize(3);
        assertThat(products).allSatisfy(p ->
                assertThat(p.name()).containsIgnoringCase("TV")
        );
    }

    @Test
    void givenSampleCsvWithFilterDisabled_whenParse_thenReturnsAllDataRows() throws IOException {
        ReflectionTestUtils.setField(parser, "tvFilterEnabled", false);
        String csvPath = classpathResourceToTempFile("test-data/sample_electronics.csv");

        List<TvProduct> products = parser.parse(csvPath);

        // all 5 data rows (header excluded)
        assertThat(products).hasSize(5);
    }

    @Test
    void givenEmptyCsvWithOnlyHeader_whenParse_thenReturnsEmptyListWithoutError() throws IOException {
        String csvPath = classpathResourceToTempFile("test-data/empty.csv");

        List<TvProduct> products = parser.parse(csvPath);

        assertThat(products).isEmpty();
    }

    @Test
    void givenMalformedCsvWithOneBadRow_whenParse_thenSkipsBadRowAndReturnsRest() throws IOException {
        String csvPath = classpathResourceToTempFile("test-data/malformed.csv");

        // malformed.csv has 2 valid TV rows and 1 row with only 2 columns
        List<TvProduct> products = parser.parse(csvPath);

        assertThat(products).hasSize(2);
    }

    @Test
    void givenSampleCsvRow_whenParse_thenRatingsAreCorrectlyParsedAsDouble() throws IOException {
        String csvPath = classpathResourceToTempFile("test-data/sample_electronics.csv");

        List<TvProduct> products = parser.parse(csvPath);

        // Samsung row has rating 4.2
        TvProduct samsung = products.stream()
                .filter(p -> p.brand().equals("Samsung"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Samsung product not found"));
        assertThat(samsung.ratings()).isEqualTo(4.2);
    }

    @Test
    void givenSampleCsvRowWithCommaInRatingsCount_whenParse_thenNoOfRatingsIsCorrectInteger() throws IOException {
        String csvPath = classpathResourceToTempFile("test-data/sample_electronics.csv");

        List<TvProduct> products = parser.parse(csvPath);

        // Samsung row has no_of_ratings "22,497" which should become 22497
        TvProduct samsung = products.stream()
                .filter(p -> p.brand().equals("Samsung"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Samsung product not found"));
        assertThat(samsung.noOfRatings()).isEqualTo(22497);
    }

    @Test
    void givenCsvWithUtf8Bom_whenParse_thenBomIsTransparentAndFirstProductParsedCorrectly()
            throws IOException {
        String bomCsvPath = createBomCsvTempFile("test-data/sample_electronics.csv");

        List<TvProduct> products = parser.parse(bomCsvPath);

        // BOM must not corrupt the first product name / brand
        assertThat(products).isNotEmpty();
        TvProduct first = products.get(0);
        // brand must be a recognisable word, not garbled BOM characters
        assertThat(first.brand()).isNotEqualTo("Unknown");
        assertThat(first.name()).doesNotStartWith("\uFEFF");
    }
}
