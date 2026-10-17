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
 * Serving a route's terminal action once the pipeline has admitted the request: proxying it,
 * relaying a gRPC or WebSocket exchange, serving an asset, or answering with a redirect.
 * <p>
 * <strong>Contents.</strong> {@link de.cuioss.sheriff.gateway.edge.terminal.TerminalDispatch}, which
 * serves every terminal action, and {@link de.cuioss.sheriff.gateway.edge.terminal.RedirectStage},
 * which computes a redirect route's answer.
 * <p>
 * <strong>Dependencies.</strong> This package may depend on the edge packages
 * {@code relay}, {@code upstream}, {@code error}, {@code end}, {@code admission}, {@code request}
 * and {@code assembly}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.edge.terminal;

import org.jspecify.annotations.NullMarked;
