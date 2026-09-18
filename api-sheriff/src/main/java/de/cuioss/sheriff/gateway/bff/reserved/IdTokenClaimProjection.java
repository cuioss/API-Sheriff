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
package de.cuioss.sheriff.gateway.bff.reserved;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;


import de.cuioss.sheriff.token.commons.transport.ParserConfig;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.sheriff.token.validation.json.MapRepresentation;
import org.jspecify.annotations.Nullable;

/**
 * Projects the claims of an already-validated ID token onto their <strong>native JSON types</strong>
 * for the session/user-info endpoint's {@link UserInfoEndpoint.ClaimSource} seam.
 * <p>
 * <strong>Why a re-parse.</strong> The token library's {@code ClaimValue} model carries only string,
 * string-list and date-time shapes: an object or array claim reaches it as the Java {@code toString()}
 * of the parsed value, and a number or boolean as its string spelling. Disclosing that model would
 * hand the browser {@code "[A, B]"} where the ID token carries {@code ["A","B"]}. This projection
 * instead base64url-decodes the payload segment of {@link IdTokenContent#getRawToken()} and parses it
 * with the library's own bounded JSON parser ({@link MapRepresentation#fromJson} over the default
 * {@link ParserConfig}), so every claim keeps the type it has in the token: {@link Map} for an
 * object, {@link List} for an array, {@link Number}, {@link Boolean} and {@link String}.
 * <p>
 * <strong>No new trust decision.</strong> The token handed in has already passed signature,
 * issuer, audience and expiry validation, and the signature was verified over exactly the payload
 * bytes decoded here. API Sheriff configures no JWE, so the raw ID token is always a compact JWS
 * whose second segment is the payload.
 * <p>
 * <strong>Bounded and fail-closed.</strong> The token library's {@link ParserConfig} limits apply:
 * the decoded payload may not exceed {@link ParserConfig#getMaxPayloadSize()}, a string may not exceed
 * {@link ParserConfig#getMaxStringLength()} (enforced by the parser), structures may not nest deeper
 * than {@link ParserConfig#getMaxNestingDepth()} and an array may not hold more than
 * {@link ParserConfig#getMaxArraySize()} elements. A token that is not a three-segment compact JWS, a
 * payload that is not valid base64url or JSON, or a payload beyond any limit is refused with an
 * {@link IllegalArgumentException} — no partial claim map is ever returned. A top-level claim whose
 * value is {@code null} is omitted, exactly as an absent claim. Nothing about the claim content is
 * logged.
 * <p>
 * Usage:
 * <pre>{@code
 * UserInfoEndpoint.ClaimSource source =
 *         session -> IdTokenClaimProjection.project(idBridge.validateRefreshedIdToken(session.idToken()));
 * }</pre>
 * <p>
 * Thread-safety: stateless apart from the immutable shared {@link ParserConfig}; safe for concurrent
 * use. Every returned map, nested map and list is unmodifiable.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class IdTokenClaimProjection {

    /** The library's default parser limits, shared because the configuration is immutable. */
    private static final ParserConfig PARSER_CONFIG = ParserConfig.builder().build();

    /** Header, payload and signature — the segment count of a compact JWS. */
    private static final int COMPACT_JWS_SEGMENTS = 3;

    private static final int PAYLOAD_SEGMENT = 1;

    private IdTokenClaimProjection() {
    }

    /**
     * Projects every claim of the validated ID token to its native JSON type.
     *
     * @param idToken the validated ID token whose raw form is re-parsed
     * @return an unmodifiable, insertion-ordered (sorted by claim name) map of claim name to
     *         {@link Map}, {@link List}, {@link Number}, {@link Boolean} or {@link String} value; top-level
     *         {@code null} claims are omitted
     * @throws IllegalArgumentException when the raw token is not a compact JWS, its payload is not
     *                                  base64url-encoded JSON, or the payload exceeds a parser limit
     */
    public static Map<String, Object> project(IdTokenContent idToken) {
        Objects.requireNonNull(idToken, "idToken");
        byte[] payload = decodePayload(idToken.getRawToken());
        MapRepresentation parsed;
        try {
            parsed = MapRepresentation.fromJson(PARSER_CONFIG.getDslJson(), payload);
        } catch (IOException e) {
            throw new IllegalArgumentException("ID token payload is not a parsable JSON object", e);
        }
        // MapRepresentation drops null-valued members and does not keep the payload's member order, so
        // the claim names are sorted to give a deterministic insertion order.
        Map<String, Object> projected = new LinkedHashMap<>();
        new TreeMap<>(parsed.data()).forEach((name, value) -> projected.put(name, immutableCopy(value, 1)));
        return Collections.unmodifiableMap(projected);
    }

    private static byte[] decodePayload(@Nullable String rawToken) {
        if (rawToken == null) {
            throw new IllegalArgumentException("ID token carries no raw form");
        }
        String[] segments = rawToken.split("\\.", -1);
        if (segments.length != COMPACT_JWS_SEGMENTS) {
            throw new IllegalArgumentException("ID token is not a three-segment compact JWS");
        }
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(segments[PAYLOAD_SEGMENT]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ID token payload is not base64url-encoded", e);
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("ID token payload is empty");
        }
        if (payload.length > PARSER_CONFIG.getMaxPayloadSize()) {
            throw new IllegalArgumentException("ID token payload exceeds the parser payload limit");
        }
        return payload;
    }

    /**
     * Deep-copies one parsed JSON value into an unmodifiable structure, enforcing the nesting-depth and
     * array-size limits on the way.
     *
     * @param value the parsed value
     * @param depth the nesting depth of the structure holding {@code value} (the payload object is 1)
     * @return the immutable copy
     */
    private static @Nullable Object immutableCopy(@Nullable Object value, int depth) {
        return switch (value) {
            case null -> null;
            case Map<?, ?> map -> copyObject(map, depth + 1);
            case List<?> list -> copyArray(list, depth + 1);
            case String string -> string;
            case Number number -> number;
            case Boolean bool -> bool;
            default -> throw new IllegalArgumentException(
                    "ID token payload holds an unsupported JSON value type: " + value.getClass().getName());
        };
    }

    private static Map<String, @Nullable Object> copyObject(Map<?, ?> map, int depth) {
        requireDepth(depth);
        Map<String, @Nullable Object> copy = new LinkedHashMap<>();
        map.forEach((key, member) -> copy.put(String.valueOf(key), immutableCopy(member, depth)));
        return Collections.unmodifiableMap(copy);
    }

    private static List<@Nullable Object> copyArray(List<?> list, int depth) {
        requireDepth(depth);
        if (list.size() > PARSER_CONFIG.getMaxArraySize()) {
            throw new IllegalArgumentException("ID token payload holds an array beyond the parser array limit");
        }
        List<@Nullable Object> copy = new ArrayList<>(list.size());
        for (Object element : list) {
            copy.add(immutableCopy(element, depth));
        }
        return Collections.unmodifiableList(copy);
    }

    private static void requireDepth(int depth) {
        if (depth > PARSER_CONFIG.getMaxNestingDepth()) {
            throw new IllegalArgumentException("ID token payload nests beyond the parser nesting limit");
        }
    }
}
