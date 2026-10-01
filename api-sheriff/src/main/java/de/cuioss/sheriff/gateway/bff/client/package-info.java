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
/**
 * The confidential client's own key material (ADR-0057) — the keys the BFF signs with when it
 * authenticates to the identity provider and when it proves possession of its tokens.
 * <p>
 * {@link de.cuioss.sheriff.gateway.bff.client.ClientSigningKey} is the one type of the package. It
 * resolves one key per purpose — the {@code private_key_jwt} client-assertion key and the DPoP proof
 * key — either from a PEM file the operator names by path or, when none is configured, by
 * generating one at startup. It derives the key id and the signing algorithm from the key and hands
 * the key to the token engine without disclosing it.
 * <p>
 * <strong>Nothing in this package logs or renders a key.</strong> No type here has an accessor that
 * returns private key material, no {@code toString()} carries a key member, and every refusal names
 * a configuration field and a defect — never the content of a key file nor its configured path.
 * <p>
 * The package is framework-agnostic (no CDI, no JAX-RS/Vert.x coupling) and introduces no new
 * dependency — key decoding, key generation and signing use the JDK's own providers.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.client;

import org.jspecify.annotations.NullMarked;
