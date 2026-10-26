/*
 * Copyright © 2025-present CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.integration;

import java.util.Arrays;
import java.util.List;

/**
 * Reads the primary instance's Prometheus exposition on the management port and sums the samples of
 * one meter, so a suite can prove a request moved a series rather than only that the series exists.
 * <p>
 * The one scraping helper of the module: {@link MetricsIT} asserts the metrics surface itself through
 * it, and the suites that assert a pre-trust rejection read the TRUE event of an answer rendered as
 * the unrouted {@code 404} from {@value #ERRORS_TOTAL} through it — the status a caller is shown no
 * longer names the cause, the {@code event} label still does.
 * <p>
 * Micrometer exposes a meter only once it has been recorded, so a series that was never recorded sums
 * to {@code 0.0}; a delta is therefore always measured as "after strictly greater than before".
 * Concurrent suites can only add to a series, never subtract from it, which keeps that comparison
 * sound while other classes run.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class PrometheusScrape {

    /** The gateway's error counter, labelled {@code route}, {@code category} and {@code event}. */
    static final String ERRORS_TOTAL = "sheriff_errors_total";

    private PrometheusScrape() {
        // static helpers only
    }

    /**
     * Scrapes the metrics endpoint of the primary instance.
     *
     * @return the Prometheus exposition body
     */
    static String scrape() {
        return BaseIntegrationTest.givenManagement()
                .relaxedHTTPSValidation()
                .when()
                .get("/metrics")
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    /**
     * Sums every sample of one meter in a Prometheus exposition body across the labelled series that
     * carry every one of {@code requiredLabels}. Only sample lines are considered (see
     * {@link #samples(String, String)}). A meter with no matching series — for example one not yet
     * recorded — sums to {@code 0.0}.
     *
     * @param body           the scraped exposition body
     * @param meterName      the exposed meter name, including any {@code _total} suffix Micrometer appends
     * @param requiredLabels label fragments every counted series must contain, such as
     *                       {@code route="httpbin-proxy"}
     * @return the summed sample value of the matching series
     */
    static double sum(String body, String meterName, String... requiredLabels) {
        double sum = 0.0;
        for (String line : samples(body, meterName)) {
            if (Arrays.stream(requiredLabels).allMatch(line::contains)) {
                int lastSpace = line.lastIndexOf(' ');
                if (lastSpace >= 0) {
                    sum += Double.parseDouble(line.substring(lastSpace + 1).strip());
                }
            }
        }
        return sum;
    }

    /**
     * Returns every labelled sample line of one meter in a Prometheus exposition body. A sample line
     * starts with the meter name followed directly by its label set, so {@code # HELP} /
     * {@code # TYPE} comment lines (which begin with {@code #}) and meters that merely share the name
     * as a prefix are never returned.
     *
     * @param body      the scraped exposition body
     * @param meterName the exposed meter name, including any {@code _total} suffix Micrometer appends
     * @return the meter's sample lines, in exposition order
     */
    static List<String> samples(String body, String meterName) {
        return body.lines().filter(line -> line.startsWith(meterName + "{")).toList();
    }

    /**
     * The {@value #ERRORS_TOTAL} count of one event on one route.
     *
     * @param body    the scraped exposition body
     * @param routeId the config-fixed route id, the {@code route} label
     * @param event   the {@code EventType} name, the {@code event} label
     * @return the summed count, {@code 0.0} when the series was never recorded
     */
    static double errorCount(String body, String routeId, String event) {
        return sum(body, ERRORS_TOTAL, "route=\"" + routeId + "\"", "event=\"" + event + "\"");
    }
}
