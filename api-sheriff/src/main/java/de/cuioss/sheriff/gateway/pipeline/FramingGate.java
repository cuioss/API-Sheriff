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
package de.cuioss.sheriff.gateway.pipeline;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

/**
 * D3b GW-02 anti-request-smuggling / framing gate, run once at stage 1, before route selection.
 * <p>
 * The gate rejects these framing-desync vectors with a 400
 * {@link EventType#SECURITY_FILTER_VIOLATION} before a request can reach the upstream:
 * <ul>
 *   <li><strong>CL+TE</strong>: {@code Content-Length} and {@code Transfer-Encoding} both present,
 *       the classic front-end/back-end desync primer;</li>
 *   <li><strong>CL.CL</strong>: more than one {@code Content-Length} field, or a single field
 *       carrying a comma-separated value list;</li>
 *   <li><strong>TE.TE</strong>: a {@code Transfer-Encoding} that is repeated, or whose single value
 *       is anything other than exactly {@code chunked} (compared case-insensitively, with no
 *       trimming and no list parsing). {@code chunked, identity}, {@code xchunked} and a second
 *       {@code Transfer-Encoding} field are the shapes two parsers disagree on, so the gate admits
 *       only the one spelling every parser reads the same way. No configuration relaxes it;</li>
 *   <li><strong>body on a bodyless method</strong>: a declared body (or {@code Transfer-Encoding})
 *       on {@code GET} or {@code HEAD}. The declared-{@code Content-Length} leg is the only part
 *       of this gate an operator can relax, and only for {@code GET}, via
 *       {@code security_defaults.allow_get_with_content_length_body}; a body-present {@code GET}
 *       carrying no declared {@code Content-Length} is not {@code Content-Length}-framed and stays
 *       rejected, exactly as {@code Transfer-Encoding} on a bodyless method does;</li>
 *   <li><strong>framing/trust-header strip via {@code Connection}</strong>: a {@code Connection}
 *       token naming a framing header ({@code Content-Length} / {@code Transfer-Encoding} /
 *       {@code Host}) or a trust header ({@code Authorization} / {@code Forwarded} /
 *       {@code X-Forwarded-*}), which would drop that header hop-by-hop and reopen the desync.</li>
 * </ul>
 * The gate inspects the inbound request headers, which are immutable for the lifetime of the
 * request. The forwarding headers stage 5 regenerates are a separate set built for the upstream
 * call, so the edge does not run the gate a second time.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class FramingGate {

    private static final Set<HttpMethod> BODYLESS_METHODS = EnumSet.of(HttpMethod.GET, HttpMethod.HEAD);

    private static final Set<String> PROTECTED_HEADERS = Set.of(
            "content-length", "transfer-encoding", "host",
            "authorization", "forwarded",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port");

    private static final String TRANSFER_ENCODING = "Transfer-Encoding";

    /** The only transfer coding the gate admits; any other value, or a repeated field, is rejected. */
    private static final String CHUNKED = "chunked";

    private final boolean allowGetWithContentLengthBody;

    /**
     * Creates a gate with the boot-resolved {@code GET}-body posture.
     *
     * @param allowGetWithContentLengthBody the resolved
     *                                      {@code security_defaults.allow_get_with_content_length_body}
     *                                      opt-in. When {@code true}, a {@code Content-Length}-framed
     *                                      body is admitted on {@code GET} only;
     *                                      {@code Transfer-Encoding} on {@code GET} and any body on
     *                                      {@code HEAD} remain rejected
     */
    public FramingGate(boolean allowGetWithContentLengthBody) {
        this.allowGetWithContentLengthBody = allowGetWithContentLengthBody;
    }

    /**
     * Asserts framing integrity on the request's header set.
     *
     * @param request the in-flight request context
     * @throws GatewayException with {@link EventType#SECURITY_FILTER_VIOLATION} on any framing vector
     */
    public void process(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        rejectConflictingFraming(request);
        rejectBodyOnBodylessMethod(request);
        rejectAmbiguousTransferEncoding(request);
        rejectFramingHeaderStrip(request);
    }

    /**
     * Rejects the TE.TE shape: a {@code Transfer-Encoding} that is repeated or is not exactly
     * {@code chunked}.
     * <p>
     * It runs after the two checks above on purpose. A request that also carries
     * {@code Content-Length}, or that uses a bodyless method, is already rejected there with the
     * detail text those checks have always produced, so this check adds rejections without changing
     * an existing one. The value is compared as received: it is not trimmed and not split on commas,
     * because any leniency here is exactly the room an obfuscated coding needs to be read as
     * {@code chunked} by one parser and as something else by the next.
     */
    private static void rejectAmbiguousTransferEncoding(PipelineRequest request) {
        List<String> transferEncodings = request.headerValues(TRANSFER_ENCODING);
        if (transferEncodings.isEmpty()) {
            return;
        }
        if (transferEncodings.size() > 1) {
            throw violation("Multiple Transfer-Encoding headers present");
        }
        if (!CHUNKED.equalsIgnoreCase(transferEncodings.getFirst())) {
            throw violation("Transfer-Encoding is not exactly chunked");
        }
    }

    private static void rejectConflictingFraming(PipelineRequest request) {
        // RFC 7230 §3.3.2: a message carrying more than one Content-Length field — whether sent as
        // multiple Content-Length headers or as a single field with a comma-separated value list —
        // is ambiguous and MUST be rejected, since it is a classic HTTP request-smuggling vector.
        // This is checked before the CL+TE coexistence rule below.
        List<String> contentLengths = request.headerValues("Content-Length");
        if (contentLengths.size() > 1) {
            throw violation("Multiple Content-Length headers present");
        }
        if (!contentLengths.isEmpty() && contentLengths.getFirst().indexOf(',') >= 0) {
            throw violation("Content-Length header carries a comma-separated value list");
        }
        if (request.hasHeader("Content-Length") && request.hasHeader(TRANSFER_ENCODING)) {
            throw violation("Content-Length and Transfer-Encoding both present");
        }
    }

    /**
     * Rejects a body on a bodyless method, with the {@code GET} opt-in applied to one leg only.
     * <p>
     * The three conditions are deliberately no longer one disjunction. The {@code Transfer-Encoding}
     * leg is evaluated first and unconditionally: chunked framing on an otherwise-bodyless method is
     * the shape the smuggling defences exist to constrain, so no configuration relaxes it. Only the
     * declared-{@code Content-Length} leg consults the opt-in, and only for {@code GET} —
     * {@code HEAD} is unaffected on every leg. The opt-in therefore admits nothing that is not
     * {@code Content-Length}-framed: a {@code GET} whose body is signalled without a positive
     * declared {@code Content-Length} still falls through to the rejection below, so the gate
     * re-asserts that bound itself rather than inheriting it from an upstream stage.
     */
    private void rejectBodyOnBodylessMethod(PipelineRequest request) {
        if (!BODYLESS_METHODS.contains(request.method())) {
            return;
        }
        if (request.hasHeader(TRANSFER_ENCODING)) {
            // Same detail text as the body legs below: with the opt-in off every rejection this gate
            // produced before the split is preserved bit-for-bit, message included.
            throw violation("Body present on bodyless method " + request.method());
        }
        if (allowGetWithContentLengthBody && request.method() == HttpMethod.GET
                && request.declaredContentLength() > 0) {
            return;
        }
        if (request.bodyPresent() || request.declaredContentLength() > 0) {
            throw violation("Body present on bodyless method " + request.method());
        }
    }

    private static void rejectFramingHeaderStrip(PipelineRequest request) {
        for (String connectionValue : request.headerValues("Connection")) {
            for (String token : connectionValue.split(",")) {
                if (PROTECTED_HEADERS.contains(token.strip().toLowerCase(Locale.ROOT))) {
                    throw violation("Connection header attempts to strip protected header " + token.strip());
                }
            }
        }
    }

    private static GatewayException violation(String detail) {
        return new GatewayException(EventType.SECURITY_FILTER_VIOLATION, "Framing rejected: " + detail);
    }
}
