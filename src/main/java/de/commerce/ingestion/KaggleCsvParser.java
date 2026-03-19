package de.commerce.ingestion;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import de.commerce.model.TvProduct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the Kaggle Amazon Electronics CSV file and produces a list of TvProduct instances.
 * Applies TV filtering when ingestion.tv.filter=true.
 *
 * CSV header: (index),name,main_category,sub_category,image,link,ratings,no_of_ratings,discount_price,actual_price
 * Note: the dataset has an unnamed index column as the first column.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KaggleCsvParser {

    // Column indices account for the leading unnamed index column
    private static final int COL_NAME = 1;
    private static final int COL_MAIN_CATEGORY = 2;
    private static final int COL_SUB_CATEGORY = 3;
    private static final int COL_IMAGE = 4;
    private static final int COL_LINK = 5;
    private static final int COL_RATINGS = 6;
    private static final int COL_NO_OF_RATINGS = 7;
    private static final int COL_DISCOUNT_PRICE = 8;
    private static final int COL_ACTUAL_PRICE = 9;
    private static final int EXPECTED_COLUMNS = 10;

    @Value("${ingestion.tv.filter:true}")
    private boolean tvFilterEnabled;

    @Value("${ingestion.inr-to-eur-rate:0.011}")
    private double inrToEurRate;

    private final TvNameParser tvNameParser;

    public List<TvProduct> parse(String csvFilePath) throws IOException {
        Path path = Path.of(csvFilePath);
        if (!Files.exists(path)) {
            throw new IOException("CSV file not found: " + path.toAbsolutePath());
        }

        Charset charset = detectCharset(path);
        log.info("Reading CSV file '{}' with charset {}", csvFilePath, charset);

        List<TvProduct> results = new ArrayList<>();
        int totalRows = 0;
        int skippedRows = 0;

        try (Reader reader = new InputStreamReader(new FileInputStream(path.toFile()), charset);
             CSVReader csvReader = buildCsvReader(reader)) {

            String[] header = csvReader.readNext();
            if (header == null) {
                log.warn("CSV file is empty: {}", csvFilePath);
                return results;
            }
            log.debug("CSV header: {}", String.join(", ", header));

            String[] row;
            while ((row = csvReader.readNext()) != null) {
                totalRows++;
                if (row.length < EXPECTED_COLUMNS) {
                    skippedRows++;
                    log.debug("Skipping malformed row {} (only {} columns)", totalRows, row.length);
                    continue;
                }
                if (tvFilterEnabled && !isTvRow(row)) {
                    continue;
                }
                TvProduct product = buildProduct(row);
                results.add(product);
            }

        } catch (CsvValidationException e) {
            throw new IOException("CSV validation error while reading: " + csvFilePath, e);
        }

        log.info("CSV parsing complete: {} total rows read, {} skipped (malformed), {} TV products extracted",
                totalRows, skippedRows, results.size());
        return results;
    }

    private boolean isTvRow(String[] row) {
        String name = row[COL_NAME];
        if (name == null) return false;
        // main_category "tv, audio & cameras" is too broad (covers phones, audio etc.).
        // Reliable signal: product name contains " TV" (as word) AND a size indicator (inches/cm).
        boolean hasTvWord = name.contains(" TV") || name.startsWith("TV ") || name.endsWith(" TV")
                || name.toLowerCase().contains("television");
        boolean hasSizeIndicator = name.toLowerCase().contains("inches") || name.toLowerCase().contains(" cm ");
        return hasTvWord && hasSizeIndicator;
    }

    private TvProduct buildProduct(String[] row) {
        String name = sanitize(row[COL_NAME]);
        String subCategory = sanitize(row[COL_SUB_CATEGORY]);
        String imageUrl = sanitize(row[COL_IMAGE]);
        String productUrl = sanitize(row[COL_LINK]);
        String priceRaw = sanitize(row[COL_DISCOUNT_PRICE]);
        String actualPriceRaw = sanitize(row[COL_ACTUAL_PRICE]);
        String ratingsStr = sanitize(row[COL_RATINGS]);
        String noOfRatingsStr = sanitize(row[COL_NO_OF_RATINGS]);

        String productId = tvNameParser.buildProductId(name);
        String brand = tvNameParser.parseBrand(name);
        Integer screenSizeInch = tvNameParser.parseScreenSizeInch(name);
        String panelType = tvNameParser.parsePanelType(name);
        String resolution = tvNameParser.parseResolution(name);
        Double priceNumeric = tvNameParser.parsePrice(priceRaw);
        Double actualPriceNumeric = tvNameParser.parsePrice(actualPriceRaw);
        Double priceEur = convertToEur(priceNumeric);
        Double actualPriceEur = convertToEur(actualPriceNumeric);
        boolean isSmartTv = tvNameParser.isSmartTv(name);
        Double ratings = parseDouble(ratingsStr);
        Integer noOfRatings = parseNoOfRatings(noOfRatingsStr);

        TvProduct partial = new TvProduct(
                productId, name, brand, screenSizeInch, panelType, resolution,
                priceRaw, priceNumeric, actualPriceRaw, actualPriceNumeric,
                priceEur, actualPriceEur,
                ratings, noOfRatings, imageUrl, productUrl, subCategory,
                isSmartTv, null
        );

        String description = tvNameParser.buildDescriptionForEmbedding(partial);

        return new TvProduct(
                productId, name, brand, screenSizeInch, panelType, resolution,
                priceRaw, priceNumeric, actualPriceRaw, actualPriceNumeric,
                priceEur, actualPriceEur,
                ratings, noOfRatings, imageUrl, productUrl, subCategory,
                isSmartTv, description
        );
    }

    private Double convertToEur(Double inrPrice) {
        if (inrPrice == null) {
            return null;
        }
        return Math.round(inrPrice * inrToEurRate * 100.0) / 100.0;
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.debug("Could not parse double value: '{}'", value);
            return null;
        }
    }

    private Integer parseNoOfRatings(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.trim().replace(",", "");
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            log.debug("Could not parse no_of_ratings value: '{}'", value);
            return null;
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Charset detectCharset(Path path) {
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            byte[] bom = new byte[3];
            int read = fis.read(bom, 0, 3);
            if (read >= 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
                log.debug("BOM detected, using UTF-8");
                return StandardCharsets.UTF_8;
            }
        } catch (IOException e) {
            log.debug("Could not read BOM, defaulting to UTF-8");
        }
        return StandardCharsets.UTF_8;
    }

    private CSVReader buildCsvReader(Reader reader) {
        CSVParser parser = new CSVParserBuilder()
                .withSeparator(',')
                .withQuoteChar('"')
                .withIgnoreLeadingWhiteSpace(true)
                .build();
        return new CSVReaderBuilder(reader)
                .withCSVParser(parser)
                .build();
    }
}
