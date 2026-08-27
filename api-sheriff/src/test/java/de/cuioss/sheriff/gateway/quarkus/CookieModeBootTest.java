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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;


import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Boots the gateway in cookie mode with <strong>no</strong> {@code session.encryption_key} configured
 * and asserts it comes up active (D6).
 * <p>
 * Omitting the key selects the generate-on-startup key mode — a fully supported production mode whose
 * key is fresh per boot, so sessions do not survive a restart — and the point of this test is that the
 * gateway <em>boots</em> in it rather than refusing to start. The fixture under
 * {@code config/cookieboot} carries one {@code require: session} route so the session floor is real.
 * <p>
 * OIDC discovery is lazy (resolved on first engine use), so this starts no Docker container and
 * reaches no network — it runs in the ordinary surefire phase, before the Docker integration suite.
 */
@QuarkusTest
@TestProfile(CookieModeBootTest.CookieBootProfile.class)
@DisplayName("Cookie-mode boot — generate-on-startup key and the UNSUPPORTED destruction capability")
class CookieModeBootTest {

    @Inject
    BffRuntime runtime;

    /** Points the gateway at the cookie-mode boot fixture instead of the default testboot config. */
    public static final class CookieBootProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("sheriff.config.dir", "target/test-classes/config/cookieboot");
        }
    }

    @Test
    @DisplayName("Should boot an active cookie-mode runtime with no encryption_key configured")
    void shouldBootActiveWithGeneratedKey() {
        assertTrue(runtime.isActive(),
                "cookie mode with no encryption_key selects generate-on-startup — a boot, not a failure");
        assertNotNull(runtime.sessionStage(), "the require: session stage-4 runtime is wired in cookie mode");
        assertNotNull(runtime.csrfDefence());
    }

    @Test
    @DisplayName("Should report the UNSUPPORTED sid/sub capability — the back-channel path answers 404")
    void shouldReportUnsupportedIdpDestruction() {
        // The stateless binding holds no server-side index, so it reports IdpDestruction.UNSUPPORTED.
        // The observable consequence of that capability at the runtime's own surface is the
        // back-channel logout gate: the reserved path stays registered and answers a deliberate 404.
        BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.BACKCHANNEL_LOGOUT,
                new BffRuntime.ReservedHttpRequest("", null, null, null, null, "logout_token=abc.def.ghi", "POST"),
                Instant.parse("2026-07-27T10:00:00Z"));

        assertEquals(404, response.status(),
                "a binding reporting UNSUPPORTED gates the back-channel endpoint off");
        assertEquals("no-store", response.headers().get("Cache-Control"),
                "the gated outcome is served uncacheable, exactly as the 200/400 outcomes are");
    }
}
