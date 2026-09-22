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

import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;


import io.quarkus.qute.Engine;
import io.quarkus.qute.Expression;
import io.quarkus.qute.HtmlEscaper;
import io.quarkus.qute.IfSectionHelper;
import io.quarkus.qute.LoopSectionHelper;
import io.quarkus.qute.SetSectionHelper;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateException;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.TemplateNode;
import io.quarkus.qute.ValueResolvers;
import io.quarkus.qute.Variant;
import io.quarkus.qute.WhenSectionHelper;
import org.jspecify.annotations.Nullable;

/**
 * Renders the application portal page — and the negotiated HTML error pages — through one
 * <em>standalone</em> Qute engine that HTML-escapes every value it writes.
 * <p>
 * <strong>Why a standalone engine.</strong> The Quarkus-managed, injectable {@code Engine} carries the
 * {@code cdi:}, {@code config:} and {@code inject:} namespace resolvers, which would expose beans and
 * configuration to an operator-authored template. This renderer therefore builds its own engine and
 * never touches the managed one:
 * <ul>
 * <li>the default value resolvers <em>except</em> the raw resolver, so {@code raw} and {@code safe}
 * never resolve to an unescaped {@code RawString};</li>
 * <li>{@link HtmlEscaper} as the result mapper for the {@code text/html} variant every template is
 * parsed with, so every rendered value is escaped;</li>
 * <li>no namespace resolver, no template locator and no reflection resolver — the data model is a
 * fixed map of plain values ({@link PortalPageModel}), which is also what keeps rendering native-image
 * safe without any reflection registration;</li>
 * <li>only the {@code if}, {@code for}/{@code each}, {@code let}/{@code set} and
 * {@code when}/{@code switch} sections. {@code eval} is deliberately absent — it would parse a model
 * value, such as the signed-in user's name, as template code — and {@code include}/{@code insert}
 * have no locator to resolve against; a template using any other section is refused as a parse
 * error.</li>
 * </ul>
 * <p>
 * <strong>Boot-time refusal.</strong> Building a renderer parses the template and walks every
 * expression in it, including section parameters and virtual-method arguments. Any expression part
 * naming {@code raw} or {@code safe} — as a property, a method or a filter-style {@code x|raw}
 * spelling — and any namespaced expression, refuses the template with a
 * {@link TemplateRefusedException} naming the template and the line — the second, independent layer
 * behind the missing raw resolver. A missing or unreadable file and an unparseable template are
 * refusals too, so a renderer that exists is one whose template was found, parsed and cleared of
 * escape bypasses and namespaced expressions. The boot checks do <em>not</em> validate expression
 * keys against {@link PortalPageModel}: a reference to a key the model does not carry parses
 * cleanly and fails only at render time, under strict rendering.
 * <p>
 * Immutable after construction; thread-safe — a parsed Qute template may be rendered concurrently.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PortalRenderer {

    /** Classpath location of the built-in template, included in the native image. */
    static final String BUILT_IN_RESOURCE = "portal/portal.html";

    /** File name of an operator template inside {@code portal.template_dir}. */
    static final String TEMPLATE_FILE_NAME = "portal.html";

    /** Human-readable source reported for the built-in template. */
    static final String BUILT_IN_SOURCE = "built-in";

    /** Expression parts that would bypass the HTML escaping — refused wherever they appear. */
    private static final Set<String> ESCAPE_BYPASS_PARTS = Set.of("raw", "safe");

    /** Separator between the identifier tokens of one expression part name. */
    private static final Pattern NON_IDENTIFIER = Pattern.compile("[^A-Za-z0-9_$]+");

    private static final Variant HTML = Variant.forContentType(Variant.TEXT_HTML);

    private static final Engine ENGINE = buildEngine();

    private final Template template;
    private final String source;

    private PortalRenderer(Template template, String source) {
        this.template = template;
        this.source = source;
    }

    /**
     * Builds the renderer over the built-in, CSS-free and script-free template shipped on the
     * classpath at {@code portal/portal.html}.
     *
     * @return the renderer over the built-in template
     * @throws TemplateRefusedException if the built-in template is missing from the classpath or is
     *                                  refused by the boot-time checks
     */
    public static PortalRenderer builtIn() {
        String content;
        try (InputStream stream = PortalRenderer.class.getClassLoader().getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (stream == null) {
                throw new TemplateRefusedException(BUILT_IN_SOURCE,
                        "the built-in template " + BUILT_IN_RESOURCE + " is not on the classpath", null);
            }
            content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TemplateRefusedException(BUILT_IN_SOURCE,
                    "the built-in template could not be read: " + e.getClass().getSimpleName(), null);
        }
        return fromContent(content, BUILT_IN_SOURCE);
    }

    /**
     * Builds the renderer over the operator template {@code portal.html} inside {@code templateDir}
     * ({@code portal.template_dir}).
     *
     * @param templateDir the configured template directory
     * @return the renderer over the operator template
     * @throws TemplateRefusedException if the template file is missing or unreadable, cannot be
     *                                  parsed, or uses an escape-bypass or namespaced expression
     */
    public static PortalRenderer fromDirectory(Path templateDir) {
        Path file = templateDir.resolve(TEMPLATE_FILE_NAME);
        String location = file.toString();
        if (!Files.isRegularFile(file)) {
            throw new TemplateRefusedException(location, "the template file does not exist", null);
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TemplateRefusedException(location,
                    "the template file could not be read: " + e.getClass().getSimpleName(), null);
        }
        return fromContent(content, location);
    }

    /**
     * Parses {@code content} as a {@code text/html} template and applies the boot-time refusals.
     *
     * @param content  the template source
     * @param location the template location reported in a refusal and by {@link #source()}
     * @return the renderer
     * @throws TemplateRefusedException if the template cannot be parsed or uses an escape-bypass or
     *                                  namespaced expression
     */
    static PortalRenderer fromContent(String content, String location) {
        Template parsed;
        try {
            parsed = ENGINE.parse(content, HTML, location);
        } catch (TemplateException e) {
            throw new TemplateRefusedException(location, "the template cannot be parsed: " + e.getMessage(),
                    lineOf(e.getOrigin()));
        }
        refuseEscapeBypass(parsed, location);
        return new PortalRenderer(parsed, location);
    }

    /**
     * Renders the template over a data model assembled by {@link PortalPageModel}.
     *
     * @param model the fixed data model, every contract key present
     * @return the rendered, HTML-escaped page
     */
    public String render(Map<String, Object> model) {
        TemplateInstance instance = template.instance();
        model.forEach(instance::data);
        return instance.render();
    }

    /**
     * @return where the template came from: {@code built-in} or the operator template file
     */
    public String source() {
        return source;
    }

    private static Engine buildEngine() {
        return Engine.builder()
                .addSectionHelpers(new IfSectionHelper.Factory(), new LoopSectionHelper.Factory(),
                        new SetSectionHelper.Factory(), new WhenSectionHelper.Factory())
                // The Qute defaults minus ValueResolvers.rawResolver() — the resolver behind `raw`/`safe`.
                .addValueResolvers(ValueResolvers.mapEntryResolver(), ValueResolvers.mapResolver(),
                        ValueResolvers.collectionResolver(), ValueResolvers.listResolver(),
                        ValueResolvers.thisResolver(), ValueResolvers.orResolver(),
                        ValueResolvers.trueResolver(), ValueResolvers.logicalAndResolver(),
                        ValueResolvers.logicalOrResolver(), ValueResolvers.orEmpty(),
                        ValueResolvers.arrayResolver(), ValueResolvers.plusResolver(),
                        ValueResolvers.minusResolver(), ValueResolvers.modResolver(),
                        ValueResolvers.numberValueResolver(), ValueResolvers.equalsResolver(),
                        ValueResolvers.mapperResolver())
                .addResultMapper(new HtmlEscaper(List.of(Variant.TEXT_HTML)))
                .strictRendering(true)
                .build();
    }

    private static void refuseEscapeBypass(Template parsed, String location) {
        List<Expression> expressions = new ArrayList<>(parsed.getExpressions());
        for (TemplateNode node : parsed.findNodes(_ -> true)) {
            expressions.addAll(node.getExpressions());
        }
        for (Expression expression : expressions) {
            refuseExpression(expression, location);
        }
    }

    private static void refuseExpression(Expression expression, String location) {
        if (expression.hasNamespace()) {
            throw new TemplateRefusedException(location,
                    "namespaced expression {" + expression.toOriginalString()
                            + "} is not allowed — portal templates see the data model only",
                    lineOf(expression.getOrigin()));
        }
        for (Expression.Part part : expression.getParts()) {
            if (namesEscapeBypass(part.getName())) {
                throw new TemplateRefusedException(location,
                        "expression {" + expression.toOriginalString() + "} uses '" + part.getName()
                                + "', which would bypass the mandatory HTML escaping",
                        lineOf(expression.getOrigin()));
            }
            if (part.isVirtualMethod()) {
                for (Expression parameter : part.asVirtualMethod().getParameters()) {
                    refuseExpression(parameter, location);
                }
            }
        }
    }

    /**
     * Qute keeps a filter-style spelling such as {@code title|raw} as one part name, so the name is
     * split on every non-identifier character and each token is checked — a bypass spelled in any
     * separator is refused rather than left to fail, or worse to resolve, at render time.
     */
    private static boolean namesEscapeBypass(String partName) {
        for (String token : NON_IDENTIFIER.split(partName)) {
            if (ESCAPE_BYPASS_PARTS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Integer lineOf(TemplateNode.@Nullable Origin origin) {
        return origin == null ? null : origin.getLine();
    }

    /**
     * A portal template was refused at boot. Carries the template location, the reason and — where
     * the refusal is tied to a place in the template — the line, for the structured startup-abort
     * record.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public static final class TemplateRefusedException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String location;
        private final String reason;
        private final @Nullable Integer line;

        /**
         * @param location the template location ({@code built-in} or the file path)
         * @param reason   why the template was refused
         * @param line     the offending line, {@code null} when the refusal concerns the whole file
         */
        public TemplateRefusedException(String location, String reason, @Nullable Integer line) {
            super("Portal template '" + location + "' refused" + (line == null ? "" : " at line " + line)
                    + ": " + reason);
            this.location = Objects.requireNonNull(location, "location");
            this.reason = Objects.requireNonNull(reason, "reason");
            this.line = line;
        }

        /**
         * @return the template location
         */
        public String location() {
            return location;
        }

        /**
         * @return why the template was refused
         */
        public String reason() {
            return reason;
        }

        /**
         * @return the offending line, empty when the refusal concerns the whole file
         */
        public OptionalInt line() {
            return line == null ? OptionalInt.empty() : OptionalInt.of(line);
        }
    }
}
