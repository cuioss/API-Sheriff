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

/**
 * The local-directory / volume-mount {@link AssetSource} (decision: ADR-0014).
 * <p>
 * Serves confined files from a configured directory root through the shared
 * {@link PathConfinement} and {@link AssetResponseEnvelope}. This is the sealed-seam
 * member of the asset-source hierarchy; its directory-serving behaviour is
 * implemented in the local-directory asset-source deliverable.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class DirectoryAssetSource implements AssetSource {
}
