package de.commerce.model;

/**
 * Represents a single sort criterion: which field to sort on and in which direction.
 *
 * @param field the Elasticsearch field name (e.g. "priceEur", "ratings")
 * @param order "asc" or "desc"
 */
public record SortField(String field, String order) {
}
