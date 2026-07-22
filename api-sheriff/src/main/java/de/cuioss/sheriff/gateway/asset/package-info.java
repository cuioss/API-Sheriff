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
/**
 * The asset terminal action (decision: ADR-0014) — serving static content through
 * the gateway instead of proxying to an upstream.
 * <p>
 * The package is organized around three cooperating pieces, all framework-agnostic
 * (ADR-0005):
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.asset.PathConfinement} — the canonicalize-and-
 *       confine boundary that turns an untrusted request sub-path into a target proven
 *       to lie inside a configured root, rejecting the whole traversal/encoding attack
 *       class before any source is touched.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.asset.AssetResponseEnvelope} — the gateway-owned
 *       response governance: a fixed content-type map, {@code nosniff}, forced
 *       {@code no-store} for authenticated access, stripped {@code Set-Cookie}, and
 *       {@code GET}/{@code HEAD}-only serving.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.asset.AssetSource} — the sealed source seam
 *       permitting the local-directory and secondary-origin sources, carrying the
 *       auth-before-source-resolution ordering contract.</li>
 * </ul>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
package de.cuioss.sheriff.gateway.asset;
