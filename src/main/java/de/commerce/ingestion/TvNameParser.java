package de.commerce.ingestion;

import de.commerce.model.TvProduct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses structured attributes from raw TV product name strings.
 * All methods are null-safe: a null or blank input returns a sensible default.
 */
@Slf4j
@Service
public class TvNameParser {

    private static final int MIN_TV_INCH = 20;
    private static final int MAX_TV_INCH = 120;
    private static final double CM_PER_INCH = 2.54;
    private static final int MAX_PRODUCT_ID_LENGTH = 80;

    private static final Pattern BRAND_PATTERN =
            Pattern.compile("^([A-Z][A-Za-z0-9]+)");

    private static final Pattern INCHES_PATTERN =
            Pattern.compile("(\\d+)\\s*[Ii]nches?");

    private static final Pattern CM_PATTERN =
            Pattern.compile("(\\d{2,3})\\s*cm");

    private static final Pattern OLED_PATTERN =
            Pattern.compile("(?i)\\bOLED\\b");

    private static final Pattern QLED_PATTERN =
            Pattern.compile("(?i)\\bQLED\\b");

    private static final Pattern MINI_LED_PATTERN =
            Pattern.compile("(?i)Mini.?LED");

    private static final Pattern AMOLED_PATTERN =
            Pattern.compile("(?i)\\bAMOLED\\b");

    private static final Pattern LED_PATTERN =
            Pattern.compile("(?i)\\bLED\\b");

    private static final Pattern RESOLUTION_8K_PATTERN =
            Pattern.compile("(?i)\\b8K\\b");

    private static final Pattern RESOLUTION_4K_PATTERN =
            Pattern.compile("(?i)\\b(4K|Ultra\\s*HD|UHD)\\b");

    private static final Pattern RESOLUTION_FHD_PATTERN =
            Pattern.compile("(?i)\\b(Full\\s*HD|1080|FHD)\\b");

    private static final Pattern RESOLUTION_HD_PATTERN =
            Pattern.compile("(?i)\\b(HD\\s*Ready|720)\\b");

    private static final Pattern SMART_PATTERN =
            Pattern.compile("(?i)\\bSmart\\b");

    private static final Pattern PRICE_CLEANUP_PATTERN =
            Pattern.compile("[^0-9.]");

    public String parseBrand(String name) {
        if (name == null || name.isBlank()) {
            return "Unknown";
        }
        Matcher m = BRAND_PATTERN.matcher(name.trim());
        return m.find() ? m.group(1) : "Unknown";
    }

    public Integer parseScreenSizeInch(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        Matcher inchMatcher = INCHES_PATTERN.matcher(name);
        if (inchMatcher.find()) {
            return validateInchValue(Integer.parseInt(inchMatcher.group(1)));
        }

        Matcher cmMatcher = CM_PATTERN.matcher(name);
        if (cmMatcher.find()) {
            int cm = Integer.parseInt(cmMatcher.group(1));
            int inches = (int) Math.round(cm / CM_PER_INCH);
            return validateInchValue(inches);
        }

        return null;
    }

    private Integer validateInchValue(int inches) {
        if (inches >= MIN_TV_INCH && inches <= MAX_TV_INCH) {
            return inches;
        }
        return null;
    }

    public String parsePanelType(String name) {
        if (name == null || name.isBlank()) {
            return "Unknown";
        }
        if (AMOLED_PATTERN.matcher(name).find()) {
            return "AMOLED";
        }
        if (OLED_PATTERN.matcher(name).find()) {
            return "OLED";
        }
        if (QLED_PATTERN.matcher(name).find()) {
            return "QLED";
        }
        if (MINI_LED_PATTERN.matcher(name).find()) {
            return "Mini-LED";
        }
        if (LED_PATTERN.matcher(name).find()) {
            return "LED";
        }
        return "Unknown";
    }

    public String parseResolution(String name) {
        if (name == null || name.isBlank()) {
            return "Unknown";
        }
        if (RESOLUTION_8K_PATTERN.matcher(name).find()) {
            return "8K UHD";
        }
        if (RESOLUTION_4K_PATTERN.matcher(name).find()) {
            return "4K UHD";
        }
        if (RESOLUTION_FHD_PATTERN.matcher(name).find()) {
            return "Full HD";
        }
        if (RESOLUTION_HD_PATTERN.matcher(name).find()) {
            return "HD Ready";
        }
        return "Unknown";
    }

    public boolean isSmartTv(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return SMART_PATTERN.matcher(name).find();
    }

    public Double parsePrice(String price) {
        if (price == null || price.isBlank()) {
            return null;
        }
        String cleaned = PRICE_CLEANUP_PATTERN.matcher(price).replaceAll("");
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            log.debug("Could not parse price value: '{}'", price);
            return null;
        }
    }

    public String buildProductId(String name) {
        if (name == null || name.isBlank()) {
            return "unknown-product";
        }
        String slug = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_PRODUCT_ID_LENGTH) {
            slug = slug.substring(0, MAX_PRODUCT_ID_LENGTH).replaceAll("-+$", "");
        }
        return slug.isBlank() ? "unknown-product" : slug;
    }

    public String buildDescriptionForEmbedding(TvProduct p) {
        StringBuilder sb = new StringBuilder();

        String brand = p.brand() != null ? p.brand() : "Unbekannt";
        String name = p.name() != null ? p.name() : "Unbekannt";
        String panelType = p.panelType() != null ? p.panelType() : "Unknown";
        String resolution = p.resolution() != null ? p.resolution() : "Unknown";

        sb.append("Der ").append(brand).append(" ").append(name)
                .append(" ist ein ");

        if (p.screenSizeInch() != null) {
            sb.append(p.screenSizeInch()).append(" Zoll ");
        }

        sb.append(panelType).append(" Fernseher mit ")
                .append(resolution).append(" Auflösung.");

        if (Boolean.TRUE.equals(p.isSmartTv())) {
            sb.append(" Er ist ein Smart TV.");
        }

        if (p.priceEur() != null) {
            sb.append(" Preis: ").append(String.format("%.2f", p.priceEur())).append(" EUR.");
        } else if (p.priceRaw() != null && !p.priceRaw().isBlank()) {
            sb.append(" Preis: ").append(p.priceRaw()).append(".");
        }

        String ratingsStr = p.ratings() != null ? String.valueOf(p.ratings()) : "N/A";
        String countStr = p.noOfRatings() != null ? String.valueOf(p.noOfRatings()) : "0";
        sb.append(" Bewertung: ").append(ratingsStr)
                .append("/5 von ").append(countStr).append(" Kunden.");

        return sb.toString();
    }
}
