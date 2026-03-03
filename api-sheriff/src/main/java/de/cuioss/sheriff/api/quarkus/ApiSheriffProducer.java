/*
 * Copyright © 2025 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.quarkus;

import de.cuioss.sheriff.api.ApiSheriff;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * CDI producer for API Sheriff components.
 * This class provides CDI integration for the API Sheriff library in Quarkus applications.
 *
 * @author API Sheriff Team
 */
@ApplicationScoped
@RegisterForReflection
public class ApiSheriffProducer {

    private static final CuiLogger LOGGER = new CuiLogger(ApiSheriffProducer.class);

    /**
     * Produces an application-scoped ApiSheriff instance.
     *
     * @return configured ApiSheriff instance
     */
    @Produces
    @ApplicationScoped
    public ApiSheriff produceApiSheriff() {
        LOGGER.debug("Creating ApiSheriff instance");
        return new ApiSheriff();
    }
}
