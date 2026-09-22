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
package de.cuioss.sheriff.gateway.portal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.sheriff.gateway.bff.reserved.ClaimAllowlistFilter;
import de.cuioss.sheriff.gateway.bff.reserved.UserInfoEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.SessionIdentity;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.PortalConfig;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PortalEndpoint}: the portal's own reserved path. The exact, host-independent match
 * (a trailing-slash or prefix variant never matches), the method contract ({@code GET} renders,
 * {@code HEAD} answers the same status and headers with an empty body, every other method is a
 * {@code 405} carrying {@code Allow: GET, HEAD}), the inert endpoint, and the login/logout link and
 * session-identity contract of the rendered model.
 * <p>
 * The model the endpoint hands to its renderer is observed through a probe template that prints every
 * model value, so each assertion reads what a real template would render — no test double framework.
 */
@DisplayName("PortalEndpoint")
class PortalEndpointTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final String PORTAL_PATH = "/apps/portal";
    private static final String ENCODED_PORTAL_PATH = "%2Fapps%2Fportal";
    private static final String LOGIN_PATH = "/auth/login";
    private static final String LOGOUT_PATH = "/auth/logout";
    private static final String TITLE = "Application Portal";
    private static final int CACHE_SECONDS = 120;

    /**
     * Prints every model value on its own segment; an absent value renders as {@code -} so a
     * {@code null} is distinguishable from an empty string.
     */
    private static final String PROBE_TEMPLATE = "T:{title}"
            + "|S:{session.authenticated}"
            + "|U:{#if session.username}{session.username}{#else}-{/if}"
            + "|L:{#if links.login}{links.login}{#else}-{/if}"
            + "|O:{#if links.logout}{links.logout}{#else}-{/if}"
            + "|N:{#if notice}{notice}{#else}-{/if}"
            + "|C:{context_path}"
            + "|A:{#for app in apps}[{app.title}]{/for}";

    private static final PortalRenderer PROBE = PortalRenderer.fromContent(PROBE_TEMPLATE, "probe");

    private static final PortalEndpoint.SessionResolver ANONYMOUS = (cookie, now) -> SessionIdentity.anonymous();

    private static PortalConfig portal() {
        return PortalConfig.builder().path(PORTAL_PATH).title(TITLE).cacheSeconds(CACHE_SECONDS).build();
    }

    private static OidcConfig oidc(@Nullable String loginPath, @Nullable String logoutPath) {
        return OidcConfig.builder()
                .login(OidcConfig.Login.builder().path(loginPath).build())
                .logout(OidcConfig.Logout.builder().path(logoutPath).build())
                .build();
    }

    private static PortalCatalog catalog() {
        return new PortalCatalog(List.of(new PortalCatalog.Entry("Orders", "Order management", "/orders/", 1)));
    }

    private static PortalEndpoint endpoint(PortalEndpoint.SessionResolver resolver, @Nullable OidcConfig oidc,
            boolean bffActive) {
        return PortalEndpoint.of(portal(), catalog(), PROBE, resolver, oidc, bffActive, "/");
    }

    private static String render(PortalEndpoint endpoint, @Nullable String cookie) {
        return endpoint.handle(HttpMethod.GET, null, cookie, NOW).body();
    }

    @Nested
    @DisplayName("Path matching")
    class Matching {

        private final PortalEndpoint endpoint = endpoint(ANONYMOUS, null, false);

        @Test
        @DisplayName("Matches exactly the configured portal path")
        void matchesExactPath() {
            assertAll(
                    () -> assertTrue(endpoint.isActive()),
                    () -> assertTrue(endpoint.matches(PORTAL_PATH)));
        }

        @ParameterizedTest
        @ValueSource(strings = {PORTAL_PATH + "/", PORTAL_PATH + "/x", PORTAL_PATH + "x", "/apps", "/apps/",
                "/APPS/PORTAL", "/apps//portal", "", "/"})
        @DisplayName("Never matches a trailing-slash, prefix, sub-path or case variant")
        void rejectsVariants(String variant) {
            assertFalse(endpoint.matches(variant), variant);
        }

        @Test
        @DisplayName("Never matches a request whose canonical path is not yet known")
        void rejectsNullPath() {
            assertFalse(endpoint.matches(null));
        }
    }

    @Nested
    @DisplayName("Inert endpoint")
    class Inert {

        private final PortalEndpoint inert = PortalEndpoint.inert();

        @ParameterizedTest
        @ValueSource(strings = {"/", PORTAL_PATH, "/portal", ""})
        @DisplayName("Never matches any path")
        void neverMatches(String path) {
            assertAll(
                    () -> assertFalse(inert.isActive()),
                    () -> assertFalse(inert.matches(path), path),
                    () -> assertFalse(inert.matches(null)));
        }

        @Test
        @DisplayName("Refuses to answer a request")
        void refusesToHandle() {
            assertThrows(IllegalStateException.class, () -> inert.handle(HttpMethod.GET, null, null, NOW));
        }
    }

    @Nested
    @DisplayName("Method contract")
    class Methods {

        private final PortalEndpoint endpoint = endpoint(ANONYMOUS, null, false);

        @Test
        @DisplayName("GET answers 200 with the rendered HTML page and the session-free envelope")
        void getRendersPage() {
            PortalEndpoint.PortalResponse response = endpoint.handle(HttpMethod.GET, null, null, NOW);

            assertAll(
                    () -> assertEquals(200, response.status()),
                    () -> assertEquals(PortalResponseEnvelope.HTML_CONTENT_TYPE,
                            response.headers().get(PortalResponseEnvelope.CONTENT_TYPE_HEADER)),
                    () -> assertEquals(PortalResponseEnvelope.NOSNIFF,
                            response.headers().get(PortalResponseEnvelope.CONTENT_TYPE_OPTIONS_HEADER)),
                    () -> assertEquals("max-age=" + CACHE_SECONDS,
                            response.headers().get(PortalResponseEnvelope.CACHE_CONTROL_HEADER)),
                    () -> assertFalse(response.headers().containsKey(PortalResponseEnvelope.VARY_HEADER),
                            "without an active BFF runtime the page never varies on Cookie"),
                    () -> assertFalse(response.headers().containsKey(PortalEndpoint.ALLOW_HEADER)),
                    () -> assertTrue(response.body().startsWith("T:" + TITLE + "|"), response.body()),
                    () -> assertTrue(response.body().contains("|A:[Orders]"), response.body()));
        }

        @Test
        @DisplayName("GET renders through the built-in template to a complete HTML document")
        void getRendersBuiltInTemplate() {
            PortalEndpoint builtIn = PortalEndpoint.of(portal(), catalog(), PortalRenderer.builtIn(), ANONYMOUS,
                    null, false, "/");

            PortalEndpoint.PortalResponse response = builtIn.handle(HttpMethod.GET, null, null, NOW);

            assertAll(
                    () -> assertEquals(200, response.status()),
                    () -> assertTrue(response.body().startsWith("<!DOCTYPE html>"), response.body()),
                    () -> assertTrue(response.body().contains("<a href=\"/orders/\">Orders</a>"), response.body()));
        }

        @Test
        @DisplayName("HEAD answers 200 with the GET headers and an empty body")
        void headAnswersEmptyBody() {
            PortalEndpoint.PortalResponse head = endpoint.handle(HttpMethod.HEAD, null, null, NOW);
            PortalEndpoint.PortalResponse get = endpoint.handle(HttpMethod.GET, null, null, NOW);

            assertAll(
                    () -> assertEquals(200, head.status()),
                    () -> assertEquals("", head.body()),
                    () -> assertEquals(get.headers(), head.headers()));
        }

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"POST", "PUT", "DELETE", "OPTIONS", "PATCH"})
        @DisplayName("Every other method answers 405 with Allow: GET, HEAD, no-store and an empty body")
        void otherMethodsAnswer405(HttpMethod method) {
            PortalEndpoint.PortalResponse response = endpoint.handle(method, null, null, NOW);

            assertAll(
                    () -> assertEquals(405, response.status()),
                    () -> assertEquals("GET, HEAD", response.headers().get(PortalEndpoint.ALLOW_HEADER)),
                    () -> assertEquals(PortalResponseEnvelope.NO_STORE,
                            response.headers().get(PortalResponseEnvelope.CACHE_CONTROL_HEADER)),
                    () -> assertEquals("", response.body()));
        }

        @Test
        @DisplayName("A 405 never consults the session")
        void methodNotAllowedSkipsSessionResolution() {
            AtomicInteger resolutions = new AtomicInteger();
            PortalEndpoint counting = endpoint((cookie, now) -> {
                resolutions.incrementAndGet();
                return SessionIdentity.anonymous();
            }, oidc(LOGIN_PATH, LOGOUT_PATH), true);

            counting.handle(HttpMethod.POST, null, "SESSION=x", NOW);

            assertEquals(0, resolutions.get());
        }

        @Test
        @DisplayName("The response headers are unmodifiable")
        void headersAreUnmodifiable() {
            Map<String, String> headers = endpoint.handle(HttpMethod.GET, null, null, NOW).headers();

            assertThrows(UnsupportedOperationException.class, headers::clear);
        }
    }

    @Nested
    @DisplayName("Without an active BFF runtime")
    class WithoutBff {

        @Test
        @DisplayName("Without an oidc block both links are absent and the session is anonymous")
        void noOidcYieldsNoLinksAndAnonymousSession() {
            String body = render(endpoint(ANONYMOUS, null, false), null);

            assertAll(
                    () -> assertTrue(body.contains("|S:false|"), body),
                    () -> assertTrue(body.contains("|U:-|"), body),
                    () -> assertTrue(body.contains("|L:-|"), body),
                    () -> assertTrue(body.contains("|O:-|"), body));
        }

        @Test
        @DisplayName("A declared oidc block offers no link while the BFF runtime is inactive")
        void declaredOidcWithoutBffOffersNoLinks() {
            String body = render(endpoint(ANONYMOUS, oidc(LOGIN_PATH, LOGOUT_PATH), false), null);

            assertAll(
                    () -> assertTrue(body.contains("|L:-|"), body),
                    () -> assertTrue(body.contains("|O:-|"), body));
        }

        @Test
        @DisplayName("The session resolver is never consulted — every request renders anonymously")
        void sessionResolverNotConsulted() {
            PortalEndpoint.SessionResolver signedIn = (cookie, now) -> new SessionIdentity(true, "alice");

            PortalEndpoint.PortalResponse response = endpoint(signedIn, null, false)
                    .handle(HttpMethod.GET, null, "SESSION=x", NOW);

            assertAll(
                    () -> assertTrue(response.body().contains("|S:false|U:-|"), response.body()),
                    () -> assertEquals("max-age=" + CACHE_SECONDS,
                            response.headers().get(PortalResponseEnvelope.CACHE_CONTROL_HEADER)));
        }
    }

    @Nested
    @DisplayName("With an active BFF runtime")
    class WithBff {

        @Test
        @DisplayName("links.login carries the URL-encoded portal path as returnUrl; links.logout the logout path")
        void linksFollowLoginLogoutContract() {
            String body = render(endpoint(ANONYMOUS, oidc(LOGIN_PATH, LOGOUT_PATH), true), null);

            assertAll(
                    () -> assertTrue(body.contains("|L:" + LOGIN_PATH + "?returnUrl=" + ENCODED_PORTAL_PATH + "|"),
                            body),
                    () -> assertTrue(body.contains("|O:" + LOGOUT_PATH + "|"), body));
        }

        @Test
        @DisplayName("An undeclared login or logout path leaves that link absent")
        void undeclaredPathsLeaveLinksAbsent() {
            String withoutLogin = render(endpoint(ANONYMOUS, oidc(null, LOGOUT_PATH), true), null);
            String withoutLogout = render(endpoint(ANONYMOUS, oidc(LOGIN_PATH, " "), true), null);
            String withoutOidc = render(endpoint(ANONYMOUS, null, true), null);

            assertAll(
                    () -> assertTrue(withoutLogin.contains("|L:-|O:" + LOGOUT_PATH + "|"), withoutLogin),
                    () -> assertTrue(withoutLogout.contains("|O:-|"), withoutLogout),
                    () -> assertTrue(withoutOidc.contains("|L:-|O:-|"), withoutOidc));
        }

        @Test
        @DisplayName("An anonymous page is cacheable and varies on Cookie")
        void anonymousPageVariesOnCookie() {
            PortalEndpoint.PortalResponse response = endpoint(ANONYMOUS, oidc(LOGIN_PATH, LOGOUT_PATH), true)
                    .handle(HttpMethod.GET, null, null, NOW);

            assertAll(
                    () -> assertEquals("max-age=" + CACHE_SECONDS,
                            response.headers().get(PortalResponseEnvelope.CACHE_CONTROL_HEADER)),
                    () -> assertEquals("Cookie", response.headers().get(PortalResponseEnvelope.VARY_HEADER)));
        }

        @Test
        @DisplayName("The request's Cookie header and reference instant reach the session resolver")
        void cookieAndInstantReachResolver() {
            PortalEndpoint.SessionResolver echo = (cookie, now) -> "SESSION=x".equals(cookie) && NOW.equals(now)
                    ? new SessionIdentity(true, "alice")
                    : SessionIdentity.anonymous();

            String body = render(endpoint(echo, oidc(LOGIN_PATH, LOGOUT_PATH), true), "SESSION=x");

            assertTrue(body.contains("|S:true|U:alice|"), body);
        }

        @Test
        @DisplayName("A signed-in page is no-store and never varies — it is never cached at all")
        void signedInPageIsNoStore() {
            PortalEndpoint.PortalResponse response = endpoint((cookie, now) -> new SessionIdentity(true, "alice"),
                    oidc(LOGIN_PATH, LOGOUT_PATH), true).handle(HttpMethod.GET, null, "SESSION=x", NOW);

            assertAll(
                    () -> assertEquals(PortalResponseEnvelope.NO_STORE,
                            response.headers().get(PortalResponseEnvelope.CACHE_CONTROL_HEADER)),
                    () -> assertFalse(response.headers().containsKey(PortalResponseEnvelope.VARY_HEADER)));
        }
    }

    @Nested
    @DisplayName("Session identity from a live BFF session")
    class LiveSession {

        private static final Duration TTL = Duration.ofHours(1);
        private static final String SESSION_ID = "opaque-portal-session";

        private InMemorySessionStore sessionStore;
        private SessionCookieCodec sessionCodec;
        private String cookieHeader;

        @BeforeEach
        void createSession() {
            sessionStore = new InMemorySessionStore(4);
            sessionCodec = new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, TTL);
            sessionStore.create(SessionRecord.builder()
                    .sessionId(SESSION_ID)
                    .accessToken("raw-access-token")
                    .idToken("raw-id-token")
                    .sub("user-sub-1")
                    .expiresAt(NOW.plus(TTL))
                    .build(), NOW);
            cookieHeader = sessionCodec.toSetCookieHeader(SESSION_ID).split(";", 2)[0];
        }

        private PortalEndpoint endpointOver(Map<String, Object> claims) {
            UserInfoEndpoint userInfo = new UserInfoEndpoint(new ServerSessionBinding(sessionStore, sessionCodec),
                    new ClaimAllowlistFilter(List.of("sub"), List.of("sub")), session -> claims);
            return endpoint(userInfo::sessionIdentity, oidc(LOGIN_PATH, LOGOUT_PATH), true);
        }

        @Test
        @DisplayName("An authenticated session yields session.username from preferred_username")
        void usernameFromPreferredUsername() {
            PortalEndpoint endpoint = endpointOver(Map.of("sub", "user-sub-1", "preferred_username", "alice"));

            String body = render(endpoint, cookieHeader);

            assertAll(
                    () -> assertTrue(body.contains("|S:true|U:alice|"), body),
                    () -> assertFalse(body.contains("user-sub-1"), "the subject is never shown"),
                    () -> assertFalse(body.contains("raw-id-token"), "no token material reaches the page"));
        }

        @Test
        @DisplayName("A request without the session cookie renders anonymously")
        void noCookieRendersAnonymously() {
            PortalEndpoint endpoint = endpointOver(Map.of("preferred_username", "alice"));

            String body = render(endpoint, null);

            assertTrue(body.contains("|S:false|U:-|"), body);
        }
    }

    @Nested
    @DisplayName("Notice and context path")
    class NoticeAndContext {

        private final PortalEndpoint endpoint = endpoint(ANONYMOUS, null, false);

        @Test
        @DisplayName("The exact notice wire value is recognised")
        void recognisesNotice() {
            String body = endpoint.handle(HttpMethod.GET, PortalNotice.LOGGED_OUT.wireValue(), null, NOW).body();

            assertTrue(body.contains("|N:logged-out|"), body);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "LOGGED-OUT", "logged%2Dout", "<script>", "unknown"})
        @DisplayName("Any other notice value is dropped, never echoed")
        void dropsUnknownNotice(String raw) {
            String body = endpoint.handle(HttpMethod.GET, raw, null, NOW).body();

            assertTrue(body.contains("|N:-|"), body);
        }

        @Test
        @DisplayName("The configured context path reaches the model")
        void rendersContextPath() {
            PortalEndpoint withContext = PortalEndpoint.of(portal(), catalog(), PROBE, ANONYMOUS, null, false,
                    "/gateway");

            String body = render(withContext, null);

            assertTrue(body.contains("|C:/gateway|"), body);
        }
    }

    @Nested
    @DisplayName("HTML error pages")
    class ErrorPages {

        /** Prints the error block next to the members an error page must — or must not — carry. */
        private static final String ERROR_PROBE = "E:{#if error}{error.status}/{error.title}{#else}-{/if}"
                + "|S:{session.authenticated}"
                + "|U:{#if session.username}{session.username}{#else}-{/if}"
                + "|L:{#if links.login}{links.login}{#else}-{/if}"
                + "|N:{#if notice}{notice}{#else}-{/if}"
                + "|A:{#for app in apps}[{app.title}]{/for}";

        private static PortalEndpoint errorPageEndpoint(PortalEndpoint.SessionResolver resolver) {
            PortalConfig withErrorPages = PortalConfig.builder().path(PORTAL_PATH).title(TITLE)
                    .cacheSeconds(CACHE_SECONDS).errorPages(true).build();
            return PortalEndpoint.of(withErrorPages, catalog(), PortalRenderer.fromContent(ERROR_PROBE, "error-probe"),
                    resolver, oidc(LOGIN_PATH, LOGOUT_PATH), true, "/");
        }

        @Test
        @DisplayName("Error pages are enabled only by an explicit error_pages: true on an active endpoint")
        void enabledOnlyWhenDeclared() {
            PortalConfig declaredOff = PortalConfig.builder().path(PORTAL_PATH).title(TITLE).errorPages(false).build();

            assertAll(
                    () -> assertTrue(errorPageEndpoint(ANONYMOUS).errorPagesEnabled()),
                    () -> assertFalse(endpoint(ANONYMOUS, null, false).errorPagesEnabled(), "omitted means off"),
                    () -> assertFalse(PortalEndpoint.of(declaredOff, catalog(), PROBE, ANONYMOUS, null, false, "/")
                            .errorPagesEnabled()),
                    () -> assertFalse(PortalEndpoint.inert().errorPagesEnabled()));
        }

        @Test
        @DisplayName("renderError refuses on an endpoint whose error pages are off, and on the inert endpoint")
        void refusesWhenDisabled() {
            PortalEndpoint disabled = endpoint(ANONYMOUS, null, false);

            assertAll(
                    () -> assertThrows(IllegalStateException.class, () -> disabled.renderError(404)),
                    () -> assertThrows(IllegalStateException.class, () -> PortalEndpoint.inert().renderError(404)));
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 403, 404, 413, 502, 503, 504})
        @DisplayName("renderError keeps the status and renders exactly the fixed title, anonymously, no-store")
        void rendersFixedTitleAnonymouslyNoStore(int status) {
            AtomicInteger resolutions = new AtomicInteger();
            PortalEndpoint endpoint = errorPageEndpoint((cookie, now) -> {
                resolutions.incrementAndGet();
                return new SessionIdentity(true, "alice");
            });

            PortalEndpoint.PortalResponse response = endpoint.renderError(status);

            assertAll(
                    () -> assertEquals(status, response.status()),
                    () -> assertEquals("E:" + status + "/" + ErrorPageClassifier.titleFor(status)
                            + "|S:false|U:-|L:" + LOGIN_PATH + "?returnUrl=" + ENCODED_PORTAL_PATH
                            + "|N:-|A:[Orders]", response.body(),
                            "the page carries the status, the fixed title, the links and the catalog — nothing else"),
                    () -> assertEquals(0, resolutions.get(), "no session is resolved on a failure path"),
                    () -> assertEquals(PortalResponseEnvelope.NO_STORE,
                            response.headers().get(PortalResponseEnvelope.CACHE_CONTROL_HEADER)),
                    () -> assertFalse(response.headers().containsKey(PortalResponseEnvelope.VARY_HEADER),
                            "a never-stored page announces no cache variance"),
                    () -> assertEquals(PortalResponseEnvelope.HTML_CONTENT_TYPE,
                            response.headers().get(PortalResponseEnvelope.CONTENT_TYPE_HEADER)),
                    () -> assertEquals(PortalResponseEnvelope.NOSNIFF,
                            response.headers().get(PortalResponseEnvelope.CONTENT_TYPE_OPTIONS_HEADER)));
        }

        @Test
        @DisplayName("A status outside the fixed table renders the generic fallback title")
        void rendersFallbackTitle() {
            PortalEndpoint.PortalResponse response = errorPageEndpoint(ANONYMOUS).renderError(500);

            assertAll(
                    () -> assertEquals(500, response.status()),
                    () -> assertTrue(response.body().startsWith("E:500/" + ErrorPageClassifier.FALLBACK_TITLE + "|"),
                            response.body()));
        }

        @Test
        @DisplayName("The built-in template renders the error title into a complete HTML document")
        void rendersThroughBuiltInTemplate() {
            PortalConfig withErrorPages = PortalConfig.builder().path(PORTAL_PATH).title(TITLE).errorPages(true)
                    .build();
            PortalEndpoint builtIn = PortalEndpoint.of(withErrorPages, catalog(), PortalRenderer.builtIn(), ANONYMOUS,
                    null, false, "/");

            String body = builtIn.renderError(404).body();

            assertAll(
                    () -> assertTrue(body.startsWith("<!DOCTYPE html>"), body),
                    () -> assertTrue(body.contains(ErrorPageClassifier.titleFor(404)), body),
                    () -> assertFalse(body.contains("problem"), "no problem-detail vocabulary reaches the page"));
        }
    }
}
