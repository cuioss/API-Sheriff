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
 * The gateway's own reserved paths: the OIDC reserved paths of the server-mode BFF runtime and the
 * application portal, answered ahead of the route table, and the bounded event-loop read of the one
 * body-carrying reserved POST (back-channel logout).
 * <p>
 * <strong>Contents.</strong> The {@link de.cuioss.sheriff.gateway.edge.reserved.ReservedBodyReader}
 * that reads the back-channel logout body under its byte ceiling and deadline, and the
 * {@link de.cuioss.sheriff.gateway.edge.reserved.ReservedPathHandler} that answers the reserved paths
 * and the portal.
 * <p>
 * <strong>Dependencies.</strong> This package may depend on the edge packages {@code error},
 * {@code end} and {@code request}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.edge.reserved;

import org.jspecify.annotations.NullMarked;
