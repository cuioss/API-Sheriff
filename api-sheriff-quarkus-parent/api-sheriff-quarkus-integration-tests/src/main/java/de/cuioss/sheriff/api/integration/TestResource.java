/*
 * Copyright 2025 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.sheriff.api.integration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.runtime.annotations.RegisterForReflection;
import de.cuioss.sheriff.api.ApiSheriff;

/**
 * REST resource for testing API Sheriff integration in Quarkus applications.
 *
 * @author API Sheriff Team
 */
@Path("/test")
@ApplicationScoped
@RegisterForReflection
@Produces(MediaType.APPLICATION_JSON)
public class TestResource {

    @Inject
    ApiSheriff apiSheriff;

    /**
     * Health check endpoint to verify that API Sheriff is properly configured.
     * 
     * @return Response indicating the health status of API Sheriff
     */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        try {
            // Verify that ApiSheriff is properly injected and configured
            if (apiSheriff == null) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"status\":\"DOWN\",\"reason\":\"ApiSheriff not available\"}")
                    .build();
            }

            // Get status to verify it's working
            String status = apiSheriff.getStatus();
            
            return Response.ok("{\"status\":\"UP\",\"apiSheriff\":\"" + status + "\"}")
                .build();

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"status\":\"DOWN\",\"error\":\"" + e.getMessage() + "\"}")
                .build();
        }
    }

    /**
     * Test endpoint for basic API Sheriff functionality.
     * 
     * @return Response with test information
     */
    @GET
    @Path("/info")
    @Produces(MediaType.APPLICATION_JSON)
    public Response info() {
        return Response.ok("{\"message\":\"API Sheriff Integration Test\",\"version\":\"1.0.0-SNAPSHOT\"}")
            .build();
    }
}