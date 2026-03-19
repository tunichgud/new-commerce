package de.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Commerce Backend application.
 *
 * The CSV path can be overridden at startup via:
 *   --ingestion.csv.path=/absolute/path/to/file.csv
 */
@SpringBootApplication
public class CommerceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommerceApplication.class, args);
    }
}
