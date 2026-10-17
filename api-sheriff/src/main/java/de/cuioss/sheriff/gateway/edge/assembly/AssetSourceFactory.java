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

import de.cuioss.sheriff.gateway.asset.AssetSource;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;

/**
 * Factory building the live {@link AssetSource} for an asset route's resolved terminal
 * action — a directory reader for a {@code directory} source, an SSRF-guarded upstream
 * fetcher for an {@code upstream} source.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@FunctionalInterface
public interface AssetSourceFactory {

    /**
     * @param asset the resolved asset terminal action
     * @return the built asset source
     */
    AssetSource create(ResolvedAsset asset);
}
