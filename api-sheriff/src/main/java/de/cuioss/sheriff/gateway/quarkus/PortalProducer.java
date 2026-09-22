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
package de.cuioss.sheriff.gateway.quarkus;

import java.nio.file.Path;
import java.util.Objects;


import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.PortalConfig;
import de.cuioss.sheriff.gateway.portal.PortalCatalog;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.portal.PortalRenderer;
import de.cuioss.tools.logging.CuiLogger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CDI producer of the application portal's {@link PortalEndpoint} — the ADR-0005 framework seam that
 * assembles the framework-agnostic portal handler once, at boot.
 * <p>
 * With a {@code portal} block in {@code gateway.yaml} the producer builds the {@link PortalRenderer}
 * — over the operator template {@code <template_dir>/portal.html} when {@code portal.template_dir}
 * is declared, otherwise over the built-in template — and wires it with the boot-resolved
 * {@link PortalCatalog}, the {@link BffRuntime}'s session identity, the global {@code oidc} block
 * the login/logout links derive from, and the application context path. A template the renderer
 * refuses (missing, unreadable, unparseable, or using an escape-bypass or namespaced expression)
 * aborts startup through the structured {@link ConfigLogMessages.ERROR#PORTAL_TEMPLATE_REFUSED}
 * record, so a gateway never serves with a portal it cannot render safely. On success the
 * {@link ApiSheriffLogMessages.INFO#PORTAL_ENABLED} record names the path, the active entry count
 * and the template source.
 * <p>
 * Without a {@code portal} block the producer returns {@link PortalEndpoint#inert()}, which never
 * matches — the edge then behaves exactly as it did before the portal existed.
 * <p>
 * <strong>Context path.</strong> The model's {@code context_path} is {@code quarkus.http.root-path},
 * normalised to a single leading slash and no trailing slash ({@code /} for the root). The
 * normalisation absorbs the {@code //} the {@code @QuarkusTest} harness reads back for the default
 * root path (see block (f) of {@code application.properties}); the packaged artifact already reads
 * {@code /}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class PortalProducer {

    private static final CuiLogger LOGGER = new CuiLogger(PortalProducer.class);

    /** Reported for a refusal that concerns the whole template file rather than a line. */
    private static final String NO_LINE = "-";

    private static final String ROOT = "/";

    private final GatewayConfig gatewayConfig;
    private final PortalCatalog portalCatalog;
    private final BffRuntime bffRuntime;
    private final String contextPath;

    /**
     * @param gatewayConfig the bound gateway document carrying the {@code portal} and {@code oidc}
     *                      blocks
     * @param portalCatalog the catalog resolved from the enabled endpoints
     * @param bffRuntime    the BFF runtime supplying the session identity; inert for a bearer-only
     *                      gateway, which then renders every page anonymously
     * @param rootPath      the application root path, {@code quarkus.http.root-path}
     */
    @Inject
    public PortalProducer(GatewayConfig gatewayConfig, PortalCatalog portalCatalog, BffRuntime bffRuntime,
            @ConfigProperty(name = "quarkus.http.root-path", defaultValue = ROOT) String rootPath) {
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.portalCatalog = Objects.requireNonNull(portalCatalog, "portalCatalog");
        this.bffRuntime = Objects.requireNonNull(bffRuntime, "bffRuntime");
        this.contextPath = normalizeContextPath(Objects.requireNonNull(rootPath, "rootPath"));
    }

    /**
     * Produces the portal endpoint.
     * <p>
     * {@link Singleton} (a pseudo-scope, no client proxy) because {@link PortalEndpoint} is a
     * {@code final} class ArC cannot subclass to build a normal-scope proxy. The endpoint is immutable
     * and assembled once at boot, so a single instance is exact.
     *
     * @return the active endpoint for a declared {@code portal} block, otherwise the inert endpoint
     * @throws IllegalStateException when the portal template is refused — startup is aborted
     */
    @Produces
    @Singleton
    public PortalEndpoint portalEndpoint() {
        PortalConfig portal = gatewayConfig.portal();
        if (portal == null) {
            LOGGER.debug("No portal block — portal endpoint inert (no path reserved)");
            return PortalEndpoint.inert();
        }
        PortalRenderer renderer = renderer(portal);
        PortalEndpoint endpoint = PortalEndpoint.of(portal, portalCatalog, renderer, bffRuntime::sessionIdentity,
                gatewayConfig.oidc(), bffRuntime.isActive(), contextPath);
        LOGGER.info(ApiSheriffLogMessages.INFO.PORTAL_ENABLED, portal.path(), portalCatalog.entries().size(),
                renderer.source());
        return endpoint;
    }

    /**
     * Builds the renderer over the operator template when {@code template_dir} is declared, else over
     * the built-in template; a refusal is logged as the structured startup-abort record and rethrown
     * as the abort.
     */
    private static PortalRenderer renderer(PortalConfig portal) {
        String templateDir = portal.templateDir();
        try {
            return templateDir == null ? PortalRenderer.builtIn() : PortalRenderer.fromDirectory(Path.of(templateDir));
        } catch (PortalRenderer.TemplateRefusedException refused) {
            String line = refused.line().isPresent() ? String.valueOf(refused.line().getAsInt()) : NO_LINE;
            LOGGER.error(refused, ConfigLogMessages.ERROR.PORTAL_TEMPLATE_REFUSED, refused.location(), line,
                    refused.reason());
            throw new IllegalStateException("Refusing to start — portal template refused", refused);
        }
    }

    /**
     * Normalises {@code quarkus.http.root-path} to a single leading slash and no trailing slash, the
     * root itself being {@code /}.
     *
     * @param rootPath the configured root path
     * @return the normalised context path
     */
    static String normalizeContextPath(String rootPath) {
        int start = 0;
        while (start < rootPath.length() && rootPath.charAt(start) == '/') {
            start++;
        }
        int end = rootPath.length();
        while (end > start && rootPath.charAt(end - 1) == '/') {
            end--;
        }
        return ROOT + rootPath.substring(start, end);
    }
}
