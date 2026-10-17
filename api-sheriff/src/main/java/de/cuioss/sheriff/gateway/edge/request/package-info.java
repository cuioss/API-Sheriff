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
 * Turning the Vert.x request into the pipeline's request: the raw path and query pairs, the headers,
 * the authority host and the declared body length, plus the request reads every later edge step
 * shares — the parsed method, the forwarded query, the selected route, the canonical path and the
 * path remainder below a route's match key.
 * <p>
 * <strong>Contents.</strong> {@link de.cuioss.sheriff.gateway.edge.request.EdgeRequests}.
 * <p>
 * <strong>Dependencies.</strong> This package depends on no other edge package.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.edge.request;

import org.jspecify.annotations.NullMarked;
