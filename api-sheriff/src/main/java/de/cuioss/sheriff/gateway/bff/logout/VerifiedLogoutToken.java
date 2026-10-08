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
package de.cuioss.sheriff.gateway.bff.logout;

import java.util.Objects;

import de.cuioss.sheriff.token.validation.domain.token.TokenContent;
import org.jspecify.annotations.Nullable;

/**
 * A back-channel logout token whose signature has been verified: its claims, and the {@code typ}
 * value of its protected header. Both are covered by the signature. No claim and no header value has
 * been judged yet — that is {@link LogoutTokenValidator}'s part.
 *
 * @param content    the signature-verified claims
 * @param headerType the {@code typ} header parameter as the token carries it, {@code null} when the
 *                   header has none
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
public record VerifiedLogoutToken(TokenContent content, @Nullable String headerType) {

    /**
     * @throws NullPointerException when {@code content} is {@code null}
     */
    public VerifiedLogoutToken {
        Objects.requireNonNull(content, "content");
    }
}
