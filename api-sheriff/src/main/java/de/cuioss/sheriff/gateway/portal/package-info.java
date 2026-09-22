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
 * The application portal: the gateway-owned HTML overview page and the negotiated HTML error pages.
 * <p>
 * The portal reserves one exact, host-independent request path ({@code portal.path}) that the edge
 * answers ahead of the route table. The page lists the catalog — every enabled endpoint declaring an
 * {@code endpoint.catalog} block — rendered through a standalone template engine whose data model is a
 * fixed map of plain values, with HTML escaping enforced for every value and escape-bypass constructs
 * refused at boot. The same template renders an HTML page for a gateway-originated error on an
 * eligible exit when the request explicitly accepts {@code text/html}; the exhaustive classification of
 * which exits are eligible — and the guarantee that a response relayed from an origin never is — lives
 * in {@link de.cuioss.sheriff.gateway.portal.ErrorPageClassifier}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.portal;

import org.jspecify.annotations.NullMarked;
