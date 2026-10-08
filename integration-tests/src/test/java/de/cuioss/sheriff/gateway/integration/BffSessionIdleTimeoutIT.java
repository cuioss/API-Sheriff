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

import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.sleepSeconds;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.DOCKER;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.assertApplicationPortAnswers;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitReadiness;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.composeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.dockerQuietly;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.gatewayLog;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.publishedPort;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startBffGatewayWithSigningKeys;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;
import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.BffGateway;

import io.restassured.http.Cookie;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves {@code oidc.session.idle_timeout_seconds} on the native image, in both session modes: a
 * session that is used keeps working past the idle window, and a session left idle for the window is
 * answered as unauthenticated although its absolute lifetime has not elapsed.
 * <p>
 * <strong>The key acts, it does not merely parse.</strong> Each test boots a gateway whose descriptor
 * differs from a committed compose descriptor in one declared key — {@value #IDLE_TIMEOUT_KEY} — and
 * in the origin it names. Remove the key from {@link #writeDerivedDescriptor} and both tests fail:
 * the omitted key resolves to the smaller of 1800 seconds and {@code ttl_seconds}, so no session of
 * these tests would ever be idled out, and the assertion that an idle session is refused reads
 * {@code 200}. The derivation asserts that the source descriptor declares no idle timeout of its own,
 * so the declared value is this suite's and nothing else's.
 * <p>
 * <strong>Why one-off gateways.</strong> The idle timeout is a property of the whole gateway process.
 * Declared on a compose instance it would end every other suite's sessions of that instance after a
 * few seconds, so no committed descriptor of the stack declares it. Each test starts its gateway
 * through {@link OneOffGatewayContainers}, on the compose network, and removes it on every exit path.
 * Both gateways keep the client of their source descriptor ({@code integration-client}) and its two
 * key files, so they are started with the stack's signing-key directory mounted; Keycloak verifies
 * their client assertion against the key set the primary instance publishes, which is the public
 * half of the same key file.
 * <p>
 * <strong>Server mode.</strong> The last access lives in the session store and moves with every
 * request let through on a session route. The idle window is {@value #SERVER_IDLE_SECONDS} seconds:
 * the session is used at {@value #SERVER_FIRST_USE_SECONDS} and at
 * {@value #SERVER_SECOND_USE_SECONDS} seconds after the login — the second use is later than the
 * login plus the idle window, so it succeeds only because the first use moved the last access — and
 * is then left alone for the window.
 * <p>
 * <strong>Cookie mode.</strong> A stateless gateway keeps the last access in a second cookie, the
 * activity cookie, named after the session cookie with the suffix {@code -activity}. It is written
 * when the last access the request proves is at least one re-issue interval old, and never earlier.
 * That interval is the smaller of 60 seconds and half the idle window. The idle window is
 * {@value #COOKIE_IDLE_SECONDS} seconds, so the interval is {@value #COOKIE_REISSUE_INTERVAL_SECONDS}
 * seconds here and a response right after the login sets no cookie at all. The legs are:
 * <ol>
 *   <li>a request right after the login is served and sets no cookie;</li>
 *   <li>a request {@value #COOKIE_FIRST_ACTIVITY_SECONDS} seconds after the login is served, sets the
 *       activity cookie — {@code Secure}, {@code HttpOnly}, {@code SameSite=Lax}, {@code Path=/}, no
 *       {@code Domain} — and no session cookie, and is not cacheable;</li>
 *   <li>{@value #COOKIE_PAST_LOGIN_WINDOW_SECONDS} seconds after the login, later than the login plus
 *       the idle window, the browser that kept the activity cookie is still served, and a browser
 *       that dropped it is unauthenticated: without the activity cookie the last access is the
 *       login;</li>
 *   <li>the idle window after the access of leg 2, the browser that kept the activity cookie is
 *       unauthenticated as well;</li>
 *   <li>a logout clears the session cookie and the activity cookie.</li>
 * </ol>
 * <p>
 * <strong>What bounds the timing.</strong> Every wait is measured from an instant this test took
 * itself, on the side that keeps the assertion true when the machine is slow: a use that must fall
 * inside a window is scheduled with several seconds to spare, and a request that must fall outside
 * it is sent {@value #MARGIN_SECONDS} seconds after the window closed, counted from an instant taken
 * after the response of the last access was read.
 * <p>
 * <strong>What this suite does NOT prove.</strong> It replays cookie maps and asserts nothing about
 * what a browser does with the activity cookie (see {@link BffKeycloakLoginFlow}). It does not reach
 * the reserved endpoints' rule — user-info, login, logout and step-up enforce the idle timeout and do
 * not extend it — nor the sweep that removes idle server-mode sessions nobody asks for again; both
 * are proven at unit level.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Session idle timeout on one-off gateways, server mode and cookie mode")
class BffSessionIdleTimeoutIT {

    /** The declared key this suite exists for, below {@code oidc.session}. */
    private static final String IDLE_TIMEOUT_KEY = "idle_timeout_seconds";

    /** A {@code require: session} route of the shared configuration. */
    private static final String SESSION_ROUTE = "/bff-session/get";

    /** The default session-cookie name; neither source descriptor declares another. */
    private static final String SESSION_COOKIE = "__Host-sheriff-session";

    /** The activity cookie's name: the session cookie's, with the suffix the gateway appends. */
    private static final String ACTIVITY_COOKIE = SESSION_COOKIE + "-activity";

    private static final String LOGOUT_PATH = "/auth/logout";

    private static final int SERVER_IDLE_SECONDS = 12;
    private static final int SERVER_FIRST_USE_SECONDS = 7;
    private static final int SERVER_SECOND_USE_SECONDS = 14;

    private static final int COOKIE_IDLE_SECONDS = 90;
    /**
     * The gateway's re-issue interval for {@link #COOKIE_IDLE_SECONDS}: half the idle window, which is
     * below the 60-second ceiling of that interval. Leg 2 falls later than it after the login, leg 3
     * earlier than it after leg 2.
     */
    private static final int COOKIE_REISSUE_INTERVAL_SECONDS = COOKIE_IDLE_SECONDS / 2;
    private static final int COOKIE_FIRST_ACTIVITY_SECONDS = 65;
    private static final int COOKIE_PAST_LOGIN_WINDOW_SECONDS = 95;

    /** How long after a window closed the request that must fall outside it is sent. */
    private static final int MARGIN_SECONDS = 3;

    /**
     * The shortest absolute lifetime a source descriptor may declare. Far above the duration of
     * either test, so a refusal observed here is never the absolute lifetime's.
     */
    private static final int MINIMUM_SOURCE_TTL_SECONDS = 600;

    private static final long BOOT_TIMEOUT_SECONDS = 90L;

    /**
     * The ceiling of the server-mode test: the boot, a login and the 29 seconds its legs take by
     * design — the second use at {@value #SERVER_SECOND_USE_SECONDS} seconds, then the idle window and
     * the margin — with room for a slow machine. It ends a wait this test did not plan; every request
     * carries a deadline of its own as well.
     */
    private static final int SERVER_TEST_CEILING_SECONDS = 180;

    /**
     * The ceiling of the cookie-mode test: the boot, two logins and the 158 seconds its legs take by
     * design — the access at {@value #COOKIE_FIRST_ACTIVITY_SECONDS} seconds, then the idle window and
     * the margin.
     */
    private static final int COOKIE_TEST_CEILING_SECONDS = 360;

    private static final int OK = 200;
    private static final int UNAUTHENTICATED = 401;

    /** Readable by the gateway image's own user, which is not the uid the build runs as. */
    private static final String DESCRIPTOR_MODE = "rw-r--r--";

    /**
     * The fixed host ports the two gateways publish their application listener on, on every interface.
     * Above the range the compose stack publishes, and listed with every other test-started gateway's
     * port in {@code doc/development/integration-test-topology.adoc}, which also records the port
     * numbers that must not be handed out again.
     */
    private static final int SERVER_MODE_APPLICATION_PORT = 10469;
    private static final int COOKIE_MODE_APPLICATION_PORT = 10470;

    /**
     * The server-mode gateway: the committed descriptor of the {@code api-sheriff-passthrough-empty}
     * instance — server mode, no passthrough listener — retargeted at this gateway's port.
     */
    private static final Instance SERVER_MODE = new Instance("sheriff-idle-timeout-server",
            SERVER_MODE_APPLICATION_PORT,
            DOCKER.resolve(Path.of("sheriff-config-passthrough-empty", "gateway.yaml")),
            BffKeycloakLoginFlow.GATEWAY_ORIGIN, "server", SERVER_IDLE_SECONDS,
            List.of("OIDC_DPOP_KEY_FILE=/app/signing-keys/dpop-ec.pem"));

    /**
     * The cookie-mode gateway: the committed descriptor of the {@code api-sheriff-cookie} instance —
     * cookie mode, refresh off, so the session cookie is never re-sealed under the test — retargeted at
     * this gateway's port. The sealing key is a fixed test value of this suite alone.
     */
    private static final Instance COOKIE_MODE = new Instance("sheriff-idle-timeout-cookie",
            COOKIE_MODE_APPLICATION_PORT,
            DOCKER.resolve(Path.of("sheriff-config-cookie", "gateway.yaml")),
            BffKeycloakLoginFlow.COOKIE_GATEWAY_ORIGIN, "cookie", COOKIE_IDLE_SECONDS,
            List.of("OIDC_DPOP_KEY_FILE=/app/signing-keys/dpop-ec.pem",
                    "SESSION_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(
                            "idle-timeout-sealing-key-0123456".getBytes(StandardCharsets.US_ASCII))));

    /**
     * One one-off gateway of this suite.
     *
     * @param name               the container name and network alias
     * @param port               the fixed host port of the application listener, published on every
     *                           interface
     * @param sourceDescriptor   the committed descriptor the gateway's own is derived from
     * @param sourceOrigin       the origin the committed descriptor names, which the derivation
     *                           retargets
     * @param sessionMode        the {@code oidc.session.mode} the committed descriptor must declare
     * @param idleTimeoutSeconds the idle timeout the derived descriptor declares
     * @param environment        the environment entries the descriptor's placeholders need
     */
    private record Instance(String name, int port, Path sourceDescriptor, String sourceOrigin, String sessionMode,
    int idleTimeoutSeconds, List<String> environment) {

        String origin() {
            return "https://localhost:" + port;
        }

        Path derivedDescriptor() {
            return Path.of("target", name, "gateway.yaml");
        }
    }

    @Test
    @Timeout(SERVER_TEST_CEILING_SECONDS)
    @DisplayName("server mode: a used session outlives the idle window, an idle one is refused before its absolute lifetime")
    void serverModeSessionEndsWhenIdleAndNotWhileUsed() throws Exception {
        dockerQuietly("rm", "-f", SERVER_MODE.name());
        try {
            start(SERVER_MODE);
            Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE, SERVER_MODE.origin());
            long loggedInAt = System.nanoTime();

            sleepUntil(loggedInAt, SERVER_FIRST_USE_SECONDS);
            int firstUse = status(SERVER_MODE, session.gatewayCookies());
            sleepUntil(loggedInAt, SERVER_SECOND_USE_SECONDS);
            int secondUse = status(SERVER_MODE, session.gatewayCookies());
            long lastUsedAt = System.nanoTime();
            sleepUntil(lastUsedAt, SERVER_IDLE_SECONDS + MARGIN_SECONDS);
            int afterIdling = status(SERVER_MODE, session.gatewayCookies());

            assertAll("server-mode idle timeout of " + SERVER_IDLE_SECONDS + "s",
                    () -> assertEquals(OK, firstUse, () -> "a session used " + SERVER_FIRST_USE_SECONDS
                            + "s after its login is inside the idle window and must be served. "
                            + gatewayLog(SERVER_MODE.name())),
                    () -> assertEquals(OK, secondUse, () -> "a session used again " + SERVER_SECOND_USE_SECONDS
                            + "s after its login must be served: the first use moved its last access, so the "
                            + "idle window is counted from that use and not from the login. "
                            + gatewayLog(SERVER_MODE.name())),
                    () -> assertEquals(UNAUTHENTICATED, afterIdling, () -> "a session left idle for "
                            + (SERVER_IDLE_SECONDS + MARGIN_SECONDS) + "s must be answered as unauthenticated, "
                            + "although its absolute lifetime of at least " + MINIMUM_SOURCE_TTL_SECONDS
                            + "s has not elapsed. A 200 here means the declared " + IDLE_TIMEOUT_KEY
                            + " is not in effect. " + gatewayLog(SERVER_MODE.name())));
        } finally {
            dockerQuietly("rm", "-f", SERVER_MODE.name());
        }
    }

    @Test
    @Timeout(COOKIE_TEST_CEILING_SECONDS)
    @DisplayName("cookie mode: the activity cookie carries the last access, its absence falls back to the login, and a logout clears both cookies")
    void cookieModeSessionEndsWhenIdleAndTheActivityCookieCarriesTheLastAccess() throws Exception {
        dockerQuietly("rm", "-f", COOKIE_MODE.name());
        try {
            start(COOKIE_MODE);
            Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE, COOKIE_MODE.origin());
            long loggedInAt = System.nanoTime();
            Map<String, String> withoutActivity = session.gatewayCookies();
            assertNotNull(withoutActivity.get(SESSION_COOKIE), "precondition: the login must set " + SESSION_COOKIE);
            assertNull(withoutActivity.get(ACTIVITY_COOKIE),
                    "precondition: the login sets no activity cookie, the login instant is the first last access");

            // Leg 1 — inside the re-issue interval after the login nothing is written
            Response rightAfterLogin = request(COOKIE_MODE, withoutActivity);

            // Leg 2 — the first access at least one re-issue interval after the login writes the activity cookie
            sleepUntil(loggedInAt, COOKIE_FIRST_ACTIVITY_SECONDS);
            Response firstActivity = request(COOKIE_MODE, withoutActivity);
            long activityWrittenBy = System.nanoTime();
            Cookie activity = firstActivity.getDetailedCookies().get(ACTIVITY_COOKIE);
            assertAll("the response that records an access " + COOKIE_FIRST_ACTIVITY_SECONDS + "s after the login",
                    () -> assertEquals(OK, rightAfterLogin.statusCode(), "a fresh session must be served"),
                    () -> assertEquals(List.of(), rightAfterLogin.getHeaders().getValues("Set-Cookie"),
                            "a response inside the first " + COOKIE_REISSUE_INTERVAL_SECONDS
                                    + "s of a session must set no cookie"),
                    () -> assertEquals(OK, firstActivity.statusCode(), () -> "a session used inside the idle window of "
                            + COOKIE_IDLE_SECONDS + "s must be served. " + gatewayLog(COOKIE_MODE.name())),
                    () -> assertNotNull(activity, () -> "the response must set " + ACTIVITY_COOKIE + "; it set "
                            + cookieNames(firstActivity)),
                    () -> assertEquals(List.of(ACTIVITY_COOKIE), cookieNames(firstActivity),
                            "recording an access must set the activity cookie alone, never the session cookie"),
                    () -> assertEquals("no-store", firstActivity.getHeader("Cache-Control"),
                            "a response that carries a gateway cookie must not be cacheable"));
            assertAll("the activity cookie is hardened exactly like the session cookie it is named after",
                    () -> assertTrue(activity.isSecured(), "the __Host- prefix requires Secure"),
                    () -> assertTrue(activity.isHttpOnly(), "the activity cookie must be unreadable from script"),
                    () -> assertEquals("Lax", activity.getSameSite(), "SameSite=Lax"),
                    () -> assertEquals("/", activity.getPath(), "the __Host- prefix requires Path=/"),
                    () -> assertNull(activity.getDomain(), "the __Host- prefix requires no Domain attribute"),
                    () -> assertTrue(activity.getMaxAge() > 0,
                            "the activity cookie lives for the session's remaining absolute lifetime"));
            Map<String, String> withActivity = new HashMap<>(withoutActivity);
            withActivity.put(ACTIVITY_COOKIE, activity.getValue());

            // Leg 3 — later than the login plus the idle window
            sleepUntil(loggedInAt, COOKIE_PAST_LOGIN_WINDOW_SECONDS);
            Response keptTheActivityCookie = request(COOKIE_MODE, withActivity);
            int droppedTheActivityCookie = status(COOKIE_MODE, withoutActivity);

            // Leg 4 — the idle window after the access of leg 2
            sleepUntil(activityWrittenBy, COOKIE_IDLE_SECONDS + MARGIN_SECONDS);
            int afterIdling = status(COOKIE_MODE, withActivity);

            assertAll("cookie-mode idle timeout of " + COOKIE_IDLE_SECONDS + "s",
                    () -> assertEquals(OK, keptTheActivityCookie.statusCode(), () -> "a session whose activity cookie "
                            + "proves an access inside the idle window must be served "
                            + COOKIE_PAST_LOGIN_WINDOW_SECONDS + "s after its login. "
                            + gatewayLog(COOKIE_MODE.name())),
                    () -> assertEquals(List.of(), keptTheActivityCookie.getHeaders().getValues("Set-Cookie"),
                            "an access less than " + COOKIE_REISSUE_INTERVAL_SECONDS
                                    + "s after the last recorded one writes no cookie"),
                    () -> assertEquals(UNAUTHENTICATED, droppedTheActivityCookie, () -> "without the activity cookie "
                            + "the last access is the login, so " + COOKIE_PAST_LOGIN_WINDOW_SECONDS
                            + "s after it the session is past the idle window. A 200 here means the declared "
                            + IDLE_TIMEOUT_KEY + " is not in effect. " + gatewayLog(COOKIE_MODE.name())),
                    () -> assertEquals(UNAUTHENTICATED, afterIdling, () -> "a session left idle for "
                            + (COOKIE_IDLE_SECONDS + MARGIN_SECONDS) + "s after its last recorded access must be "
                            + "answered as unauthenticated, although its absolute lifetime of at least "
                            + MINIMUM_SOURCE_TTL_SECONDS + "s has not elapsed. " + gatewayLog(COOKIE_MODE.name())));

            // Leg 5 — a logout clears both cookies
            Session toLogOut = BffKeycloakLoginFlow.login(SESSION_ROUTE, COOKIE_MODE.origin());
            Response logout = BffKeycloakLoginFlow.gateway(toLogOut.gatewayCookies(), COOKIE_MODE.origin())
                    .redirects().follow(false)
                    .when().get(LOGOUT_PATH)
                    .then().extract().response();
            assertEquals(302, logout.statusCode(), "a logout is answered by a redirect");
            assertAll("a logout clears every cookie the cookie-mode binding sets",
                    () -> assertCleared(logout, SESSION_COOKIE),
                    () -> assertCleared(logout, ACTIVITY_COOKIE));
        } finally {
            dockerQuietly("rm", "-f", COOKIE_MODE.name());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void start(Instance instance) throws IOException {
        writeDerivedDescriptor(instance);
        startBffGatewayWithSigningKeys(new BffGateway(instance.name(), composeNetwork(), instance.name(),
                instance.port(), instance.derivedDescriptor(), "localhost.crt", "localhost.key",
                instance.environment()));
        awaitReadiness(instance.name(), "https://localhost:" + publishedPort(instance.name(), 9000),
                response -> response.statusCode() == OK, BOOT_TIMEOUT_SECONDS,
                "the gateway " + instance.name() + " to report readiness UP");
        assertApplicationPortAnswers(instance.name(), instance.origin());
    }

    private static Response request(Instance instance, Map<String, String> cookies) {
        return BffKeycloakLoginFlow.gateway(cookies, instance.origin())
                .header("Accept", "application/json")
                .redirects().follow(false)
                .when().get(SESSION_ROUTE)
                .then().extract().response();
    }

    private static int status(Instance instance, Map<String, String> cookies) {
        return request(instance, cookies).statusCode();
    }

    /** The names of the cookies a response sets, in wire order — never their values. */
    private static List<String> cookieNames(Response response) {
        return response.getHeaders().getValues("Set-Cookie").stream()
                .map(header -> header.substring(0, Math.max(0, header.indexOf('='))))
                .toList();
    }

    private static void assertCleared(Response logout, String cookieName) {
        Cookie cleared = logout.getDetailedCookies().get(cookieName);
        assertNotNull(cleared, () -> "the logout must clear " + cookieName + "; it set " + cookieNames(logout));
        assertAll(cookieName + " is cleared",
                () -> assertEquals(0, cleared.getMaxAge(), "a clearing cookie expires at once"),
                () -> assertTrue(cleared.getValue() == null || cleared.getValue().isEmpty(),
                        "a clearing cookie carries no value"));
    }

    /**
     * Sleeps until {@code seconds} have passed since {@code sinceNanos}; returns at once when they
     * already have.
     */
    private static void sleepUntil(long sinceNanos, long seconds) {
        long remainingMillis = Duration.ofSeconds(seconds).minusNanos(System.nanoTime() - sinceNanos).toMillis();
        if (remainingMillis > 0) {
            // Rounded up to whole seconds: every caller needs at least the stated distance.
            sleepSeconds((remainingMillis + 999) / 1000);
        }
    }

    /**
     * Writes the descriptor of one one-off gateway: the committed source descriptor with the idle
     * timeout declared and the origin triple retargeted at the gateway's own port. The source is
     * asserted to be what the derivation assumes — the session mode, an absolute lifetime far above
     * the test's duration, no idle timeout of its own — so a change to the committed descriptor fails
     * here instead of producing a gateway that differs from it in more than the stated ways.
     */
    private static void writeDerivedDescriptor(Instance instance) throws IOException {
        Map<String, Object> document = load(instance.sourceDescriptor());
        Map<String, Object> oidc = mapping(document.get("oidc"), "oidc");
        Map<String, Object> session = mapping(oidc.get("session"), "oidc.session");
        Map<String, Object> logout = mapping(oidc.get("logout"), "oidc.logout");
        Map<String, Object> csrf = mapping(session.get("csrf"), "oidc.session.csrf");
        Integer ttlSeconds = assertInstanceOf(Integer.class, session.get("ttl_seconds"),
                "oidc.session.ttl_seconds of " + instance.sourceDescriptor() + " must be declared");
        assertAll(instance.sourceDescriptor() + " must be the descriptor the derivation assumes",
                () -> assertEquals(instance.sessionMode(), session.get("mode"), "oidc.session.mode"),
                () -> assertFalse(session.containsKey(IDLE_TIMEOUT_KEY),
                        "no committed descriptor of the stack declares " + IDLE_TIMEOUT_KEY),
                () -> assertTrue(ttlSeconds >= MINIMUM_SOURCE_TTL_SECONDS, () -> "oidc.session.ttl_seconds must be at "
                        + "least " + MINIMUM_SOURCE_TTL_SECONDS + ", was " + ttlSeconds));

        session.put(IDLE_TIMEOUT_KEY, instance.idleTimeoutSeconds());
        List<?> trustedOrigins = assertInstanceOf(List.class, csrf.get("trusted_origins"),
                "oidc.session.csrf.trusted_origins must be a list");
        csrf.put("trusted_origins", trustedOrigins.stream()
                .map(origin -> retargeted(instance, origin, "oidc.session.csrf.trusted_origins")).toList());
        session.put("csrf", csrf);
        logout.put("post_logout_redirect_uri", retargeted(instance, logout.get("post_logout_redirect_uri"),
                "oidc.logout.post_logout_redirect_uri"));
        oidc.put("redirect_uri", retargeted(instance, oidc.get("redirect_uri"), "oidc.redirect_uri"));
        oidc.put("logout", logout);
        oidc.put("session", session);
        document.put("oidc", oidc);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Path derived = instance.derivedDescriptor();
        Files.createDirectories(derived.getParent());
        Files.writeString(derived, new Yaml(options).dump(document));
        Files.setPosixFilePermissions(derived, PosixFilePermissions.fromString(DESCRIPTOR_MODE));

        Map<String, Object> written = mapping(mapping(load(derived).get("oidc"), "oidc").get("session"), "oidc.session");
        assertEquals(instance.idleTimeoutSeconds(), written.get(IDLE_TIMEOUT_KEY),
                "the derived descriptor must declare the idle timeout this suite asserts");
    }

    private static String retargeted(Instance instance, Object value, String key) {
        String configured = String.valueOf(value);
        assertTrue(configured.startsWith(instance.sourceOrigin()), () -> key + " of " + instance.sourceDescriptor()
                + " must name the origin " + instance.sourceOrigin() + ", was " + configured);
        return instance.origin() + configured.substring(instance.sourceOrigin().length());
    }

    private static Map<String, Object> load(Path descriptor) throws IOException {
        try (Reader reader = Files.newBufferedReader(descriptor)) {
            return mapping(new Yaml().load(reader), descriptor.toString());
        }
    }

    /**
     * Reads a YAML node as a mapping with string keys. The result is a copy, so a changed nested
     * mapping is written back into its parent by the caller.
     */
    private static Map<String, Object> mapping(Object node, String what) {
        Map<?, ?> raw = assertInstanceOf(Map.class, node, () -> what + " must be a mapping");
        Map<String, Object> typed = new LinkedHashMap<>();
        raw.forEach((key, value) -> typed.put(String.valueOf(key), value));
        return typed;
    }
}
