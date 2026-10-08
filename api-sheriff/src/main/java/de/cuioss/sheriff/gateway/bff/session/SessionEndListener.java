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
package de.cuioss.sheriff.gateway.bff.session;

/**
 * Told when a session a {@link SessionStore} held has ended — it was destroyed, it expired and was
 * evicted, or it was ended to make room under a bound. A write that replaces a session's record or
 * re-issues its cookie handle is <em>not</em> an end: the session lives on under the same identity and
 * no call is made.
 * <p>
 * <strong>Calling contract.</strong> The store calls the listener after it has left its own monitor,
 * on the thread whose operation ended the session, once per ended session. An implementation may
 * therefore take locks of its own, but it must not call back into the store and must return quickly:
 * the thread is the one serving a logout, a login or the periodic sweep.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@FunctionalInterface
public interface SessionEndListener {

    /**
     * Reports one ended session.
     *
     * @param sessionId the stable identity of the session that ended — never a cookie handle
     */
    void sessionEnded(String sessionId);
}
