/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.config.model;

import java.util.Objects;
import java.util.Optional;

import lombok.Builder;

/**
 * The asset terminal action of a route, materialized once at boot (decision:
 * ADR-0014).
 * <p>
 * A route resolves to exactly one terminal action — either an
 * {@link ResolvedRoute#upstream() upstream} proxy target or this asset action,
 * never both. This record is the boot-time <em>description</em> of an asset action;
 * the live, framework-coupled source (a directory reader or an SSRF-guarded upstream
 * fetcher) is built from it by the route-runtime assembler, so the agnostic
 * {@code config.model} layer stays free of the {@code asset} runtime package.
 * <p>
 * The {@link AssetConfig.Source source} discriminator selects which resolved field is
 * carried: {@link AssetConfig.Source#DIRECTORY} carries a {@code directory} root and
 * no upstream; {@link AssetConfig.Source#UPSTREAM} carries a boot-{@code resolved}
 * upstream (a topology alias decomposed exactly like the proxy action's upstream) and
 * no directory. The {@link AccessLevel access} level is the axis the gateway-owned
 * response envelope keys its caching on ({@link AccessLevel#AUTHENTICATED} forces
 * {@code no-store}); it is derived from the route's <em>effective</em> auth posture
 * (ADR-0013), falling back to the resolving anchor's declared {@code access} only when
 * the route is effectively unauthenticated — a route or endpoint that strengthens a
 * {@code public}-access anchor's auth floor with its own {@code auth} block still
 * resolves to {@link AccessLevel#AUTHENTICATED} here.
 *
 * @param source    the asset content source discriminator (mandatory)
 * @param access    the effective access level of the serving route (mandatory), the
 *                  axis the response envelope keys governed caching on
 * @param directory the configured directory root, present for
 *                  {@link AssetConfig.Source#DIRECTORY} and empty otherwise
 * @param upstream  the boot-resolved secondary origin, present for
 *                  {@link AssetConfig.Source#UPSTREAM} and empty otherwise
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record ResolvedAsset(AssetConfig.Source source, AccessLevel access, Optional<String> directory,
Optional<ResolvedUpstream> upstream) {

    /**
     * Canonical constructor requiring {@code source} and {@code access}, normalizing
     * absent optionals, and enforcing the source-to-field invariant — a
     * {@link AssetConfig.Source#DIRECTORY} action carries a directory root and no
     * upstream, an {@link AssetConfig.Source#UPSTREAM} action carries a resolved
     * upstream and no directory.
     */
    public ResolvedAsset {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(access, "access");
        directory = Objects.requireNonNullElse(directory, Optional.empty());
        upstream = Objects.requireNonNullElse(upstream, Optional.empty());
        switch (source) {
            case DIRECTORY -> {
                if (directory.isEmpty() || upstream.isPresent()) {
                    throw new IllegalArgumentException(
                            "DIRECTORY asset requires a directory root and no upstream target");
                }
            }
            case UPSTREAM -> {
                if (upstream.isEmpty() || directory.isPresent()) {
                    throw new IllegalArgumentException(
                            "UPSTREAM asset requires a resolved upstream and no directory root");
                }
            }
        }
    }

    /**
     * Creates a {@link AssetConfig.Source#DIRECTORY} asset action.
     *
     * @param root   the configured directory root (mandatory)
     * @param access the serving route's effective access level (mandatory)
     * @return the resolved directory asset action
     */
    public static ResolvedAsset directory(String root, AccessLevel access) {
        return new ResolvedAsset(AssetConfig.Source.DIRECTORY, access, Optional.of(root), Optional.empty());
    }

    /**
     * Creates a {@link AssetConfig.Source#UPSTREAM} asset action.
     *
     * @param upstream the boot-resolved secondary origin (mandatory)
     * @param access   the serving route's effective access level (mandatory)
     * @return the resolved upstream asset action
     */
    public static ResolvedAsset upstream(ResolvedUpstream upstream, AccessLevel access) {
        return new ResolvedAsset(AssetConfig.Source.UPSTREAM, access, Optional.empty(), Optional.of(upstream));
    }
}
