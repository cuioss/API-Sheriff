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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;


import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PortalRenderer}: every model value is HTML-escaped, escape-bypass and namespaced
 * expressions are refused at build with the offending line, unusable template files are refused, and
 * the built-in template renders the complete data model.
 */
@EnableGeneratorController
@DisplayName("PortalRenderer")
class PortalRendererTest {

    /** The fixed special-character title of the request's escaping acceptance criterion. */
    private static final String SPECIAL_TITLE = "K.Beispiel <&\"'>";
    private static final String SPECIAL_TITLE_ESCAPED = "K.Beispiel &lt;&amp;&quot;&#39;&gt;";

    /** Every character the escaping guarantee covers, as it appears in a model value. */
    private static final String HOSTILE = "<script>alert(\"x\" & 'y')</script>";
    private static final String HOSTILE_ESCAPED =
            "&lt;script&gt;alert(&quot;x&quot; &amp; &#39;y&#39;)&lt;/script&gt;";

    /** A template printing every value of the data model, one per line. */
    private static final String EVERY_VALUE_TEMPLATE = """
            T:{title}
            {#for app in apps}A:{app.title}|{app.description}|{app.entry}
            {/for}U:{session.username}
            L:{links.login}|{links.logout}
            N:{notice}
            C:{context_path}
            {#if error}E:{error.status}|{error.title}{/if}
            """;

    private static PortalPageModel.PortalPageModelBuilder model(String title) {
        return PortalPageModel.builder()
                .title(title)
                .catalog(PortalCatalog.empty())
                .contextPath("/");
    }

    private static PortalCatalog catalogOf(String title, String description, String entry) {
        return new PortalCatalog(List.of(new PortalCatalog.Entry(title, description, entry, null)));
    }

    @Nested
    @DisplayName("Escaping")
    class Escaping {

        @Test
        @DisplayName("Escapes the fixed special-character title and never emits its raw characters")
        void escapesFixedSpecialCharacterTitle() {
            PortalRenderer renderer = PortalRenderer.fromContent("<h1>{title}</h1>", "test");

            String html = renderer.render(model(SPECIAL_TITLE).build().toMap());

            assertEquals("<h1>" + SPECIAL_TITLE_ESCAPED + "</h1>", html);
        }

        @Test
        @DisplayName("Escapes <, >, &, \" and ' in every value of the data model")
        void escapesEveryModelValue() {
            PortalRenderer renderer = PortalRenderer.fromContent(EVERY_VALUE_TEMPLATE, "test");
            PortalPageModel pageModel = model(HOSTILE)
                    .catalog(catalogOf(HOSTILE, HOSTILE, HOSTILE))
                    .authenticated(true)
                    .username(HOSTILE)
                    .loginLink(HOSTILE)
                    .logoutLink(HOSTILE)
                    .contextPath(HOSTILE)
                    .errorStatus(404)
                    .errorTitle(HOSTILE)
                    .build();

            String html = renderer.render(pageModel.toMap());

            assertAll(
                    () -> assertFalse(html.contains("<script>"), html),
                    () -> assertFalse(html.contains("'y'"), html),
                    () -> assertTrue(html.contains("T:" + HOSTILE_ESCAPED), html),
                    () -> assertTrue(html.contains("A:" + HOSTILE_ESCAPED + "|" + HOSTILE_ESCAPED + "|"
                            + HOSTILE_ESCAPED), html),
                    () -> assertTrue(html.contains("U:" + HOSTILE_ESCAPED), html),
                    () -> assertTrue(html.contains("L:" + HOSTILE_ESCAPED + "|" + HOSTILE_ESCAPED), html),
                    () -> assertTrue(html.contains("C:" + HOSTILE_ESCAPED), html),
                    () -> assertTrue(html.contains("E:404|" + HOSTILE_ESCAPED), html));
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.NON_BLANK_STRINGS, minSize = 1, maxSize = 40, count = 20)
        @DisplayName("Escapes generated titles, descriptions and usernames wrapped in markup characters")
        void escapesGeneratedValues(String generated) {
            String value = "<" + generated + "&\"'>";
            PortalRenderer renderer = PortalRenderer.fromContent(EVERY_VALUE_TEMPLATE, "test");
            PortalPageModel pageModel = model(value)
                    .catalog(catalogOf(value, value, "/entry/"))
                    .authenticated(true)
                    .username(value)
                    .build();

            String html = renderer.render(pageModel.toMap());

            String escaped = escape(value);
            assertAll(
                    () -> assertTrue(html.contains("T:" + escaped), html),
                    () -> assertTrue(html.contains("A:" + escaped + "|" + escaped + "|/entry/"), html),
                    () -> assertTrue(html.contains("U:" + escaped), html),
                    () -> assertFalse(html.contains(value), html));
        }

        @Test
        @DisplayName("Renders the recognised notice through the template")
        void rendersNotice() {
            PortalRenderer renderer = PortalRenderer.fromContent("N:{notice}", "test");

            String html = renderer.render(model("Portal").notice(PortalNotice.LOGGED_OUT).build().toMap());

            assertEquals("N:logged-out", html);
        }

        private static String escape(String value) {
            StringBuilder escaped = new StringBuilder();
            for (char character : value.toCharArray()) {
                switch (character) {
                    case '<' -> escaped.append("&lt;");
                    case '>' -> escaped.append("&gt;");
                    case '&' -> escaped.append("&amp;");
                    case '"' -> escaped.append("&quot;");
                    case '\'' -> escaped.append("&#39;");
                    default -> escaped.append(character);
                }
            }
            return escaped.toString();
        }
    }

    @Nested
    @DisplayName("Boot-time refusals")
    class Refusals {

        @ParameterizedTest
        @ValueSource(strings = {"{title.raw}", "{title.safe}", "{title.raw()}", "{title|raw}",
                "{#if title.raw}x{/if}", "{#for app in apps}{app.title.safe}{/for}",
                "{title.or(title.raw)}"})
        @DisplayName("Refuses every escape-bypass spelling with the offending line")
        void refusesEscapeBypass(String expression) {
            String content = "<p>ok</p>\n<p>" + expression + "</p>\n";

            PortalRenderer.TemplateRefusedException refused = assertThrows(
                    PortalRenderer.TemplateRefusedException.class,
                    () -> PortalRenderer.fromContent(content, "operator/portal.html"));

            assertAll(
                    () -> assertEquals("operator/portal.html", refused.location()),
                    () -> assertEquals(OptionalInt.of(2), refused.line(), refused.getMessage()),
                    () -> assertTrue(refused.getMessage().contains("operator/portal.html"),
                            refused.getMessage()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"{cdi:someBean}", "{config:quarkus.http.port}", "{inject:someBean.value}"})
        @DisplayName("Refuses a namespaced expression — no namespace resolver reaches a template")
        void refusesNamespacedExpression(String expression) {
            PortalRenderer.TemplateRefusedException refused = assertThrows(
                    PortalRenderer.TemplateRefusedException.class,
                    () -> PortalRenderer.fromContent("<p>" + expression + "</p>", "test"));

            assertEquals(OptionalInt.of(1), refused.line(), refused.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {"{#if title}unclosed", "{#eval title /}", "{#include other /}", "{title"})
        @DisplayName("Refuses an unparseable template or an unsupported section")
        void refusesUnparseableTemplate(String content) {
            PortalRenderer.TemplateRefusedException refused = assertThrows(
                    PortalRenderer.TemplateRefusedException.class,
                    () -> PortalRenderer.fromContent(content, "test"));

            assertTrue(refused.reason().startsWith("the template cannot be parsed"), refused.getMessage());
        }

        @Test
        @DisplayName("Refuses a template directory without portal.html")
        void refusesMissingTemplateFile(@TempDir Path templateDir) {
            PortalRenderer.TemplateRefusedException refused = assertThrows(
                    PortalRenderer.TemplateRefusedException.class,
                    () -> PortalRenderer.fromDirectory(templateDir));

            assertAll(
                    () -> assertEquals(templateDir.resolve("portal.html").toString(), refused.location()),
                    () -> assertEquals(OptionalInt.empty(), refused.line()));
        }

        @Test
        @DisplayName("Refuses a template directory that does not exist")
        void refusesMissingTemplateDirectory(@TempDir Path root) {
            Path missing = root.resolve("absent");

            assertThrows(PortalRenderer.TemplateRefusedException.class,
                    () -> PortalRenderer.fromDirectory(missing));
        }

        @Test
        @DisplayName("Refuses an operator template file carrying an escape bypass")
        void refusesOperatorFileWithBypass(@TempDir Path templateDir) throws IOException {
            Files.writeString(templateDir.resolve("portal.html"), "<h1>{title}</h1>\n\n<p>{title.raw}</p>",
                    StandardCharsets.UTF_8);

            PortalRenderer.TemplateRefusedException refused = assertThrows(
                    PortalRenderer.TemplateRefusedException.class,
                    () -> PortalRenderer.fromDirectory(templateDir));

            assertEquals(OptionalInt.of(3), refused.line(), refused.getMessage());
        }
    }

    @Nested
    @DisplayName("Operator template")
    class OperatorTemplate {

        @Test
        @DisplayName("Renders an operator template loaded from the template directory")
        void rendersOperatorTemplate(@TempDir Path templateDir) throws IOException {
            Files.writeString(templateDir.resolve("portal.html"), "<h1>{title}</h1>", StandardCharsets.UTF_8);

            PortalRenderer renderer = PortalRenderer.fromDirectory(templateDir);

            assertAll(
                    () -> assertEquals("<h1>Portal</h1>", renderer.render(model("Portal").build().toMap())),
                    () -> assertEquals(templateDir.resolve("portal.html").toString(), renderer.source()));
        }
    }

    @Nested
    @DisplayName("Built-in template")
    class BuiltIn {

        @Test
        @DisplayName("Renders a complete authenticated error model")
        void rendersCompleteModel() {
            PortalRenderer renderer = PortalRenderer.builtIn();
            PortalPageModel pageModel = model(SPECIAL_TITLE)
                    .catalog(catalogOf("Orders", "Order management", "/orders/"))
                    .authenticated(true)
                    .username("alice")
                    .logoutLink("/auth/logout")
                    .notice(PortalNotice.LOGGED_OUT)
                    .errorStatus(503)
                    .errorTitle("Service Unavailable")
                    .build();

            String html = renderer.render(pageModel.toMap());

            assertAll(
                    () -> assertEquals(PortalRenderer.BUILT_IN_SOURCE, renderer.source()),
                    () -> assertTrue(html.startsWith("<!DOCTYPE html>"), html),
                    () -> assertTrue(html.contains("<h1>" + SPECIAL_TITLE_ESCAPED + "</h1>"), html),
                    () -> assertTrue(html.contains("<a href=\"/orders/\">Orders</a>"), html),
                    () -> assertTrue(html.contains("Order management"), html),
                    () -> assertTrue(html.contains("<strong>alice</strong>"), html),
                    () -> assertTrue(html.contains("<a href=\"/auth/logout\">Sign out</a>"), html),
                    () -> assertTrue(html.contains("You have been signed out."), html),
                    () -> assertTrue(html.contains("503 Service Unavailable"), html),
                    () -> assertFalse(html.contains("<script"), html),
                    () -> assertFalse(html.contains("style"), html));
        }

        @Test
        @DisplayName("Renders an anonymous overview with an empty catalog")
        void rendersEmptyCatalog() {
            PortalRenderer renderer = PortalRenderer.builtIn();

            String html = renderer.render(model("Portal").loginLink("/auth/login?returnUrl=%2F").build().toMap());

            assertAll(
                    () -> assertTrue(html.contains("No applications are available."), html),
                    () -> assertTrue(html.contains("Not signed in."), html),
                    () -> assertTrue(html.contains("<a href=\"/auth/login?returnUrl=%2F\">Sign in</a>"), html),
                    () -> assertFalse(html.contains("<ul>"), html),
                    () -> assertFalse(html.contains("signed out"), html),
                    () -> assertFalse(html.contains("could not complete"), html));
        }

        @Test
        @DisplayName("Offers neither login nor logout when no link is configured")
        void rendersWithoutLinks() {
            String html = PortalRenderer.builtIn().render(model("Portal").build().toMap());

            assertAll(
                    () -> assertFalse(html.contains("Sign in"), html),
                    () -> assertFalse(html.contains("Sign out"), html));
        }
    }
}
