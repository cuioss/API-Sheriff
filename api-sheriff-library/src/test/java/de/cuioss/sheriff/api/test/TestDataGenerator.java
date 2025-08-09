package de.cuioss.sheriff.api.test;

import lombok.experimental.UtilityClass;

/**
 * Test data generator for API Sheriff tests
 */
@UtilityClass
public class TestDataGenerator {
    
    /**
     * Generates a test API key
     * @return test API key
     */
    public static String generateApiKey() {
        return "test-api-key-" + System.currentTimeMillis();
    }
    
    /**
     * Generates a test endpoint URL
     * @return test endpoint URL
     */
    public static String generateEndpoint() {
        return "https://api.example.com/v1/test-" + System.currentTimeMillis();
    }
}