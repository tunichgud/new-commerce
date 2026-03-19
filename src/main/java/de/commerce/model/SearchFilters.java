package de.commerce.model;

import java.util.List;
import java.util.Map;

/**
 * Optional filter criteria for TV product searches.
 * All fields are nullable; only non-null fields are applied as query filters.
 */
public record SearchFilters(
        String brand,
        Integer screenSizeInch,
        String panelType,
        Double maxPriceEur,
        Double minRating,
        List<SortField> sortFields
) {

    /**
     * Merges a base {@link SearchFilters} with a map of field changes.
     * <ul>
     *   <li>If {@code changes} is null or empty, {@code base} is returned unchanged.</li>
     *   <li>Non-null values in {@code changes} overwrite the corresponding field in {@code base}.</li>
     *   <li>Explicitly null values in {@code changes} clear (null-out) the corresponding field.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public static SearchFilters merge(SearchFilters base, Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            return base;
        }

        String brand         = base.brand();
        Integer screenSize   = base.screenSizeInch();
        String panelType     = base.panelType();
        Double maxPrice      = base.maxPriceEur();
        Double minRating     = base.minRating();
        List<SortField> sort = base.sortFields();

        if (changes.containsKey("brand")) {
            brand = changes.get("brand") != null ? changes.get("brand").toString() : null;
        }
        if (changes.containsKey("screenSizeInch")) {
            Object v = changes.get("screenSizeInch");
            if (v == null) {
                screenSize = null;
            } else if (v instanceof Number n) {
                screenSize = n.intValue();
            } else {
                try { screenSize = Integer.parseInt(v.toString()); } catch (NumberFormatException ignored) { screenSize = null; }
            }
        }
        if (changes.containsKey("panelType")) {
            panelType = changes.get("panelType") != null ? changes.get("panelType").toString() : null;
        }
        if (changes.containsKey("maxPriceEur")) {
            Object v = changes.get("maxPriceEur");
            if (v == null) {
                maxPrice = null;
            } else if (v instanceof Number n) {
                maxPrice = n.doubleValue();
            } else {
                try { maxPrice = Double.parseDouble(v.toString()); } catch (NumberFormatException ignored) { maxPrice = null; }
            }
        }
        if (changes.containsKey("minRating")) {
            Object v = changes.get("minRating");
            if (v == null) {
                minRating = null;
            } else if (v instanceof Number n) {
                minRating = n.doubleValue();
            } else {
                try { minRating = Double.parseDouble(v.toString()); } catch (NumberFormatException ignored) { minRating = null; }
            }
        }
        if (changes.containsKey("sortFields")) {
            sort = (List<SortField>) changes.get("sortFields");
        }

        return new SearchFilters(brand, screenSize, panelType, maxPrice, minRating, sort);
    }
}
