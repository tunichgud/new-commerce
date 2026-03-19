package de.commerce.ingestion;

import de.commerce.model.TvProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TvNameParser.
 * No Spring context, no external dependencies.
 */
class TvNameParserTest {

    private TvNameParser parser;

    @BeforeEach
    void setUp() {
        parser = new TvNameParser();
    }

    // -------------------------------------------------------------------------
    // parseBrand
    // -------------------------------------------------------------------------

    @Nested
    class ParseBrand {

        @Test
        void givenNameStartingWithSamsung_whenParseBrand_thenReturnsSamsung() {
            assertThat(parser.parseBrand("Samsung 80 cm (32 inches) Full HD TV")).isEqualTo("Samsung");
        }

        @Test
        void givenNameStartingWithLg_whenParseBrand_thenReturnsLg() {
            assertThat(parser.parseBrand("LG 55 Zoll OLED")).isEqualTo("LG");
        }

        @Test
        void givenNullName_whenParseBrand_thenReturnsUnknown() {
            assertThat(parser.parseBrand(null)).isEqualTo("Unknown");
        }

        @Test
        void givenEmptyName_whenParseBrand_thenReturnsUnknown() {
            assertThat(parser.parseBrand("")).isEqualTo("Unknown");
        }

        @Test
        void givenNameStartingWithDigit_whenParseBrand_thenReturnsUnknown() {
            assertThat(parser.parseBrand("123abc Smart TV")).isEqualTo("Unknown");
        }
    }

    // -------------------------------------------------------------------------
    // parseScreenSizeInch
    // -------------------------------------------------------------------------

    @Nested
    class ParseScreenSizeInch {

        @Test
        void givenNameWithBothCmAndInches_whenParseScreenSize_thenInchValueWins() {
            // inches pattern matches first in the method — 32 wins over 80/2.54=31
            assertThat(parser.parseScreenSizeInch("Samsung 80 cm (32 inches) Full HD TV")).isEqualTo(32);
        }

        @Test
        void givenNameWithOnlyCm_whenParseScreenSize_thenConvertsToInch() {
            // 80 / 2.54 = 31.496... rounds to 31
            assertThat(parser.parseScreenSizeInch("Samsung 80 cm Full HD TV")).isEqualTo(31);
        }

        @Test
        void givenNameWith139CmAnd55Inches_whenParseScreenSize_thenReturns55() {
            assertThat(parser.parseScreenSizeInch("Samsung 139 cm (55 inches) TV")).isEqualTo(55);
        }

        @Test
        void givenNameWithInchValueBelowMinimum_whenParseScreenSize_thenReturnsNull() {
            // 15 inches is below MIN_TV_INCH=20
            assertThat(parser.parseScreenSizeInch("Kleiner 15 inches Monitor")).isNull();
        }

        @Test
        void givenNameWithInchValueAboveMaximum_whenParseScreenSize_thenReturnsNull() {
            // 130 inches is above MAX_TV_INCH=120
            assertThat(parser.parseScreenSizeInch("Riesiger 130 inches TV")).isNull();
        }

        @Test
        void givenNullName_whenParseScreenSize_thenReturnsNull() {
            assertThat(parser.parseScreenSizeInch(null)).isNull();
        }

        @Test
        void givenNameWithNoSizeInformation_whenParseScreenSize_thenReturnsNull() {
            assertThat(parser.parseScreenSizeInch("Kein Zoll hier")).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // parsePanelType
    // -------------------------------------------------------------------------

    @Nested
    class ParsePanelType {

        @Test
        void givenNameWithAmoled_whenParsePanelType_thenReturnsAmoled() {
            assertThat(parser.parsePanelType("Samsung AMOLED TV")).isEqualTo("AMOLED");
        }

        @Test
        void givenNameWithOled_whenParsePanelType_thenReturnsOled() {
            assertThat(parser.parsePanelType("LG OLED C3")).isEqualTo("OLED");
        }

        @Test
        void givenNameWithQled_whenParsePanelType_thenReturnsQled() {
            assertThat(parser.parsePanelType("Samsung QLED TV")).isEqualTo("QLED");
        }

        @Test
        void givenNameWithMiniLed_whenParsePanelType_thenReturnsMiniLed() {
            assertThat(parser.parsePanelType("TCL Mini-LED TV")).isEqualTo("Mini-LED");
        }

        @Test
        void givenNameWithLed_whenParsePanelType_thenReturnsLed() {
            assertThat(parser.parsePanelType("Samsung Full HD Smart LED TV")).isEqualTo("LED");
        }

        @Test
        void givenNameWithNoPanelKeyword_whenParsePanelType_thenReturnsUnknown() {
            assertThat(parser.parsePanelType("Samsung Full HD Smart TV")).isEqualTo("Unknown");
        }

        @Test
        void givenNameWithBothOledAndAmoled_whenParsePanelType_thenAmoledWins() {
            // AMOLED has priority over OLED
            assertThat(parser.parsePanelType("LG OLED AMOLED")).isEqualTo("AMOLED");
        }
    }

    // -------------------------------------------------------------------------
    // parseResolution
    // -------------------------------------------------------------------------

    @Nested
    class ParseResolution {

        @Test
        void givenNameWith4K_whenParseResolution_thenReturns4kUhd() {
            assertThat(parser.parseResolution("Samsung 4K Ultra HD TV")).isEqualTo("4K UHD");
        }

        @Test
        void givenNameWithFullHd_whenParseResolution_thenReturnsFullHd() {
            assertThat(parser.parseResolution("LG Full HD Smart TV")).isEqualTo("Full HD");
        }

        @Test
        void givenNameWithHdReady_whenParseResolution_thenReturnsHdReady() {
            assertThat(parser.parseResolution("Philips HD Ready TV")).isEqualTo("HD Ready");
        }

        @Test
        void givenNameWith8K_whenParseResolution_thenReturns8kUhd() {
            assertThat(parser.parseResolution("Sony 8K OLED TV")).isEqualTo("8K UHD");
        }

        @Test
        void givenNameWithUhdAbbreviation_whenParseResolution_thenReturns4kUhd() {
            assertThat(parser.parseResolution("Samsung UHD LED TV")).isEqualTo("4K UHD");
        }

        @Test
        void givenNameWithFhdAbbreviation_whenParseResolution_thenReturnsFullHd() {
            assertThat(parser.parseResolution("Samsung FHD TV")).isEqualTo("Full HD");
        }

        @Test
        void givenNameWithNoResolutionKeyword_whenParseResolution_thenReturnsUnknown() {
            assertThat(parser.parseResolution("Sony Bravia TV")).isEqualTo("Unknown");
        }
    }

    // -------------------------------------------------------------------------
    // isSmartTv
    // -------------------------------------------------------------------------

    @Nested
    class IsSmartTv {

        @Test
        void givenNameWithSmartKeyword_whenIsSmartTv_thenReturnsTrue() {
            assertThat(parser.isSmartTv("Samsung Smart LED TV")).isTrue();
        }

        @Test
        void givenNameWithoutSmartKeyword_whenIsSmartTv_thenReturnsFalse() {
            assertThat(parser.isSmartTv("Samsung LED TV")).isFalse();
        }

        @Test
        void givenNameWithSmartInUpperCase_whenIsSmartTv_thenReturnsTrueCaseInsensitive() {
            assertThat(parser.isSmartTv("SMART TV Hisense")).isTrue();
        }

        @Test
        void givenNullName_whenIsSmartTv_thenReturnsFalse() {
            assertThat(parser.isSmartTv(null)).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // parsePrice
    // -------------------------------------------------------------------------

    @Nested
    class ParsePrice {

        @Test
        void givenSimpleRupeePrice_whenParsePrice_thenReturnsNumericValue() {
            assertThat(parser.parsePrice("₹15,990")).isEqualTo(15990.0);
        }

        @Test
        void givenMultiGroupRupeePrice_whenParsePrice_thenStripsAllSeparators() {
            // ₹1,23,456 — Indian number grouping
            assertThat(parser.parsePrice("₹1,23,456")).isEqualTo(123456.0);
        }

        @Test
        void givenNullPrice_whenParsePrice_thenReturnsNull() {
            assertThat(parser.parsePrice(null)).isNull();
        }

        @Test
        void givenEmptyPrice_whenParsePrice_thenReturnsNull() {
            assertThat(parser.parsePrice("")).isNull();
        }

        @Test
        void givenNonNumericPrice_whenParsePrice_thenReturnsNull() {
            assertThat(parser.parsePrice("no price")).isNull();
        }

        @Test
        void givenZeroRupeePrice_whenParsePrice_thenReturnsZero() {
            assertThat(parser.parsePrice("₹0")).isEqualTo(0.0);
        }
    }

    // -------------------------------------------------------------------------
    // buildProductId
    // -------------------------------------------------------------------------

    @Nested
    class BuildProductId {

        @Test
        void givenNormalName_whenBuildProductId_thenReturnsSlug() {
            assertThat(parser.buildProductId("Samsung 80 cm TV")).isEqualTo("samsung-80-cm-tv");
        }

        @Test
        void givenVeryLongName_whenBuildProductId_thenTruncatesTo80CharsWithoutTrailingDash() {
            // Construct a name that produces a slug longer than 80 characters
            String longName = "Samsung Super Ultra HD Smart LED Television With Built-In Satellite Receiver And Wifi";
            String result = parser.buildProductId(longName);
            assertThat(result.length()).isLessThanOrEqualTo(80);
            assertThat(result).doesNotEndWith("-");
        }

        @Test
        void givenNullName_whenBuildProductId_thenReturnsUnknownProduct() {
            assertThat(parser.buildProductId(null)).isEqualTo("unknown-product");
        }

        @Test
        void givenBlankName_whenBuildProductId_thenReturnsUnknownProduct() {
            assertThat(parser.buildProductId("   ")).isEqualTo("unknown-product");
        }
    }

    // -------------------------------------------------------------------------
    // buildDescriptionForEmbedding
    // -------------------------------------------------------------------------

    @Nested
    class BuildDescriptionForEmbedding {

        @Test
        void givenFullyPopulatedProduct_whenBuildDescription_thenContainsAllKeyFields() {
            TvProduct product = new TvProduct(
                    "samsung-55-oled",
                    "Samsung 55 inches OLED 4K Smart TV",
                    "Samsung",
                    55,
                    "OLED",
                    "4K UHD",
                    "₹89,990",
                    89990.0,
                    "₹99,990",
                    99990.0,
                    989.89,
                    1099.89,
                    4.5,
                    1234,
                    "https://img.example.com/tv.jpg",
                    "https://amazon.in/dp/B001",
                    "Televisions",
                    true,
                    null
            );

            String description = parser.buildDescriptionForEmbedding(product);

            assertThat(description).contains("Samsung");
            assertThat(description).contains("55");
            assertThat(description).contains("OLED");
            assertThat(description).contains("4K UHD");
            assertThat(description).contains("989.89 EUR");
            assertThat(description).contains("4.5");
            assertThat(description).contains("1234");
        }

        @Test
        void givenProductWithNullScreenSize_whenBuildDescription_thenDoesNotContainNullZoll() {
            TvProduct product = new TvProduct(
                    "lg-oled",
                    "LG OLED Smart TV",
                    "LG",
                    null,   // no screen size
                    "OLED",
                    "4K UHD",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    4.2,
                    500,
                    null,
                    null,
                    "Televisions",
                    true,
                    null
            );

            String description = parser.buildDescriptionForEmbedding(product);

            assertThat(description).doesNotContain("null Zoll");
            assertThat(description).doesNotContain("null");
        }

        @Test
        void givenNonSmartTvProduct_whenBuildDescription_thenDoesNotContainSmartTv() {
            TvProduct product = new TvProduct(
                    "philips-32-led",
                    "Philips 32 inches LED Full HD TV",
                    "Philips",
                    32,
                    "LED",
                    "Full HD",
                    "₹12,000",
                    12000.0,
                    "₹14,000",
                    14000.0,
                    132.0,
                    154.0,
                    3.8,
                    200,
                    null,
                    null,
                    "Televisions",
                    false,  // not a smart TV
                    null
            );

            String description = parser.buildDescriptionForEmbedding(product);

            assertThat(description).doesNotContain("Smart TV");
        }
    }
}
