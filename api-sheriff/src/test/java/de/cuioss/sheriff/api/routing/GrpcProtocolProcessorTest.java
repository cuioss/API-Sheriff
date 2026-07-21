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
package de.cuioss.sheriff.api.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;


import de.cuioss.sheriff.api.config.model.HttpMethod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Contract of {@link GrpcProtocolProcessor}: it identifies as {@code grpc} and serves only the gRPC
 * call verb ({@code POST}), since every gRPC call is an HTTP/2 {@code POST} to a service/method path
 * and every other verb is outside its scope. The gRPC deltas (forced-h2 dispatch, trailer relay,
 * trailers-only rejection) are owned by the edge stages, not this processor.
 */
@DisplayName("GrpcProtocolProcessor — gRPC POST verb semantics")
class GrpcProtocolProcessorTest {

    private final GrpcProtocolProcessor processor = new GrpcProtocolProcessor();

    @Test
    @DisplayName("identifies as 'grpc'")
    void identifiesAsGrpc() {
        assertEquals("grpc", processor.id());
    }

    @Test
    @DisplayName("serves exactly the POST call verb")
    void servesOnlyPost() {
        assertEquals(Set.of(HttpMethod.POST), processor.standardMethods(),
                "every gRPC call is an HTTP/2 POST, so POST is the only verb the processor serves");
    }

    @Test
    @DisplayName("supports POST")
    void supportsPost() {
        assertTrue(processor.supports(HttpMethod.POST));
    }

    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, names = "POST", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("rejects every non-POST verb")
    void rejectsNonPostVerbs(HttpMethod method) {
        assertFalse(processor.supports(method),
                () -> "the gRPC processor must not serve " + method);
    }
}
