package de.commerce.model;

/**
 * Represents a TV product parsed from the Kaggle Amazon Electronics CSV dataset.
 * Uses a Java 21 record for immutability. Fields may be null when data is missing.
 */
public record TvProduct(
        String productId,
        String name,
        String brand,
        Integer screenSizeInch,
        String panelType,
        String resolution,
        String priceRaw,
        Double priceNumeric,
        String actualPriceRaw,
        Double actualPriceNumeric,
        Double priceEur,
        Double actualPriceEur,
        Double ratings,
        Integer noOfRatings,
        String imageUrl,
        String productUrl,
        String subCategory,
        Boolean isSmartTv,
        String descriptionForEmbedding
) {
}
