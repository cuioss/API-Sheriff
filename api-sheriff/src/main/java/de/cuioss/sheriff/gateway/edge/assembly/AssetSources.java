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
package de.cuioss.sheriff.gateway.edge.assembly;

import java.nio.file.Path;
import java.util.Map;

import de.cuioss.sheriff.gateway.asset.AssetSource;
import de.cuioss.sheriff.gateway.asset.DirectoryAssetSource;
import de.cuioss.sheriff.gateway.asset.UpstreamAssetSource;
import de.cuioss.sheriff.gateway.config.model.AssetDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;

/**
 * Builds the live {@link AssetSource} of every asset route at boot, and resolves the gateway-wide
 * asset content-type additions they are all constructed with.
 * <p>
 * Stateless; every method is a pure function of its arguments.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class AssetSources {

    private AssetSources() {
        // static builders only
    }

    /**
     * Builds the live {@link AssetSource} for an asset route's resolved terminal action: a
     * {@link DirectoryAssetSource} rooted at the configured directory — carrying its optional
     * {@code index} and {@code fallback} file names — for a {@code directory}
     * source, or an {@link UpstreamAssetSource} over the boot-resolved secondary origin for an
     * {@code upstream} source. Both apply the gateway's confinement and response envelope; the
     * upstream source rides its own SSRF-guarded fetch seam (ADR-0014), not the proxy data plane.
     *
     * @param asset             the route's resolved asset terminal action
     * @param assetContentTypes   the gateway-wide add-only content-type additions (see
     *                            {@link #assetContentTypesOf})
     * @param keepIdentityHeaders the route's {@code keep_identity_headers} opt-out, handed to an
     *                            {@code upstream} source; a {@code directory} source ignores it, since
     *                            it proposes no upstream header and boot validation refuses the key
     *                            on a directory route
     * @return the live asset source
     * @throws IllegalStateException when the resolved asset lacks the directory root or the upstream
     *                               its source kind requires
     */
    public static AssetSource assetSourceFor(ResolvedAsset asset, Map<String, String> assetContentTypes,
            boolean keepIdentityHeaders) {
        return switch (asset.source()) {
            case DIRECTORY -> {
                String directory = asset.directory();
                if (directory == null) {
                    throw new IllegalStateException("directory asset source requires a directory root");
                }
                yield new DirectoryAssetSource(Path.of(directory), asset.access(), asset.index(), asset.fallback(),
                        assetContentTypes);
            }
            case UPSTREAM -> {
                ResolvedUpstream upstream = asset.upstream();
                if (upstream == null) {
                    throw new IllegalStateException("upstream asset source requires a resolved upstream");
                }
                yield new UpstreamAssetSource(upstream, asset.access(), assetContentTypes, keepIdentityHeaders);
            }
        };
    }

    /**
     * Resolves the global {@code asset_defaults.content_types} block into the map every asset source
     * is constructed with. An absent block yields an empty map, which is exactly the add-only no-op:
     * the built-in extension map then governs alone, byte-for-byte as an unconfigured gateway does.
     *
     * @param gatewayConfig the bound gateway document
     * @return the declared content-type additions, empty when the block is absent
     */
    public static Map<String, String> assetContentTypesOf(GatewayConfig gatewayConfig) {
        AssetDefaultsConfig assetDefaults = gatewayConfig.assetDefaults();
        return assetDefaults == null ? Map.of() : assetDefaults.contentTypes();
    }
}
