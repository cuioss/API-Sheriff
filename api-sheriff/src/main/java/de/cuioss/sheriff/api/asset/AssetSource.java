/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.asset;

import java.util.Map;

import de.cuioss.sheriff.api.config.model.AccessLevel;
import de.cuioss.sheriff.api.config.model.HttpMethod;

/**
 * The sealed source seam for the asset terminal action (decision: ADR-0014).
 * <p>
 * An asset route resolves its bytes through exactly one {@code AssetSource}: a
 * {@link DirectoryAssetSource local directory} or an {@link UpstreamAssetSource
 * secondary origin}. Sealing the hierarchy lets the terminal-action dispatcher match
 * the two cases exhaustively while keeping every other module closed to new source
 * kinds.
 * <p>
 * <strong>Auth-before-source-resolution ordering contract.</strong> Every
 * implementation MUST be invoked only after the request pipeline has (1) authenticated
 * and authorized the request against the route's effective auth posture and (2)
 * confined the request sub-path through {@link PathConfinement}. An implementation
 * MUST NOT touch its backing store — open a file, issue an upstream call — until a
 * confined target has been produced; the confinement result is the only path an
 * implementation may act on. This ordering is what makes an {@code access:
 * authenticated} asset fail closed: no byte is read before authorization succeeds.
 * <p>
 * The gateway — not the source — governs the response headers: every implementation
 * routes its proposed headers through
 * {@link AssetResponseEnvelope#governedHeaders(String, AccessLevel, Map)} and honours
 * {@link AssetResponseEnvelope#isAllowedMethod(HttpMethod)} before serving.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public sealed interface AssetSource permits DirectoryAssetSource, UpstreamAssetSource {
}
