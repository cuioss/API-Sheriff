/*
 * Copyright © 2025 CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
