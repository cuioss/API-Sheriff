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
 * Interim minimal reverse-proxy edge for the API Sheriff gateway.
 * <p>
 * One Vert.x route per {@code path_prefix}
 * ({@link de.cuioss.sheriff.api.gateway.proxy.ProxyRoute}) forwards matched
 * requests to the upstream resolved for that route by the configuration
 * subsystem's {@link de.cuioss.sheriff.api.config.model.RouteTable}, executing the
 * blocking forward on a virtual thread. This is the outermost shell only: it is
 * kept as the edge and has its internals replaced by the real request pipeline in
 * Plan 03.
 *
 * @since 1.0
 */
package de.cuioss.sheriff.api.gateway.proxy;
