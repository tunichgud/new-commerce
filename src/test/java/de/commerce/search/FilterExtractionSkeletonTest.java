package de.commerce.search;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Skeleton acceptance tests for natural-language filter extraction.
 * All tests are @Disabled — they document the required behaviour for
 * the FilterExtractor component that parses structured search filters
 * out of free-text user queries.
 *
 * Activate once de.commerce.search.FilterExtractor (or equivalent) exists.
 *
 * AT-B012: Screen size extracted from "55 Zoll" in natural language
 * AT-B013: Brand extracted from product query
 * AT-B014: Panel type extracted (OLED, QLED, etc.)
 * AT-B015: Price range extracted from "unter 800 Euro"
 * AT-B016: Multiple filters extracted in a single query
 * AT-B017: Unrecognised query returns empty filter set (no NPE)
 */
@Disabled("AT-B012–B017: FilterExtractor not yet implemented")
class FilterExtractionSkeletonTest {

    // -------------------------------------------------------------------------
    // AT-B012: Screen size extraction from natural language
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B012: FilterExtractor.extract() not yet implemented")
    void givenQueryWith55Zoll_whenExtractFilters_thenScreenSizeIs55() {
        // FilterExtractor extractor = new FilterExtractor();
        // SearchFilters filters = extractor.extract("Ich suche einen 55 Zoll OLED Fernseher");
        // assertThat(filters.screenSizeInch()).isEqualTo(55);
        fail("AT-B012: Implement when FilterExtractor exists." +
             " Input: 'Ich suche einen 55 Zoll OLED Fernseher'" +
             " Expected: filters.screenSizeInch() == 55");
    }

    // -------------------------------------------------------------------------
    // AT-B013: Brand extraction
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B013: FilterExtractor.extract() not yet implemented")
    void givenQueryWithSamsungBrand_whenExtractFilters_thenBrandIsSamsung() {
        // SearchFilters filters = extractor.extract("Samsung Fernseher 4K günstig");
        // assertThat(filters.brand()).isEqualTo("Samsung");
        fail("AT-B013: Implement when FilterExtractor exists." +
             " Input: 'Samsung Fernseher 4K günstig'" +
             " Expected: filters.brand() == 'Samsung'");
    }

    // -------------------------------------------------------------------------
    // AT-B014: Panel type extraction
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B014: FilterExtractor.extract() not yet implemented")
    void givenQueryWithOledPanelType_whenExtractFilters_thenPanelTypeIsOled() {
        // SearchFilters filters = extractor.extract("bester OLED TV unter 1000 Euro");
        // assertThat(filters.panelType()).isEqualTo("OLED");
        fail("AT-B014: Implement when FilterExtractor exists." +
             " Input: 'bester OLED TV unter 1000 Euro'" +
             " Expected: filters.panelType() == 'OLED'");
    }

    // -------------------------------------------------------------------------
    // AT-B015: Price range extraction
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B015: FilterExtractor.extract() not yet implemented")
    void givenQueryWithPriceLimit_whenExtractFilters_thenMaxPriceIsCorrect() {
        // SearchFilters filters = extractor.extract("TV unter 800 Euro");
        // assertThat(filters.maxPriceNumeric()).isEqualTo(800.0);
        fail("AT-B015: Implement when FilterExtractor exists." +
             " Input: 'TV unter 800 Euro'" +
             " Expected: filters.maxPriceNumeric() == 800.0");
    }

    // -------------------------------------------------------------------------
    // AT-B016: Multiple filters in one query
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B016: FilterExtractor.extract() not yet implemented")
    void givenQueryWithSizeAndBrandAndPanel_whenExtractFilters_thenAllThreeFiltersPresent() {
        // SearchFilters filters = extractor.extract("Samsung 65 Zoll QLED Fernseher");
        // assertThat(filters.screenSizeInch()).isEqualTo(65);
        // assertThat(filters.brand()).isEqualTo("Samsung");
        // assertThat(filters.panelType()).isEqualTo("QLED");
        fail("AT-B016: Implement when FilterExtractor supports multi-filter extraction.");
    }

    // -------------------------------------------------------------------------
    // AT-B017: Unrecognised query returns empty filters without exception
    // -------------------------------------------------------------------------

    @Test
    @Disabled("AT-B017: FilterExtractor.extract() not yet implemented")
    void givenUnrecognisedQuery_whenExtractFilters_thenReturnsEmptyFiltersWithoutException() {
        // SearchFilters filters = extractor.extract("xyzzy foobar");
        // assertThat(filters).isNotNull();
        // assertThat(filters.screenSizeInch()).isNull();
        // assertThat(filters.brand()).isNull();
        fail("AT-B017: Implement when FilterExtractor exists." +
             " Must not throw for unrecognised input.");
    }
}
