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
package de.cuioss.sheriff.api.quarkus.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

import de.cuioss.sheriff.api.ApiSheriff;
import de.cuioss.sheriff.api.config.ApiGatewayConfig;
import de.cuioss.sheriff.api.quarkus.ApiSheriffProducer;

import de.cuioss.sheriff.api.security.RateLimiter;

/**
 * Quarkus build step processor for API Sheriff extension.
 * This class configures the API Sheriff components for Quarkus applications during build time.
 *
 * <p>The processor handles:</p>
 * <ul>
 *   <li>Feature registration for the API Sheriff extension</li>
 *   <li>CDI bean registration for automatic dependency injection</li>
 *   <li>Native image configuration for GraalVM compilation</li>
 *   <li>Reflection registration for runtime class access</li>
 * </ul>
 *
 * <h2>Build Time Configuration:</h2>
 * <p>This processor runs during Quarkus build time and prepares all necessary
 * components for runtime. It ensures that API Sheriff classes are properly
 * registered with CDI and configured for native image compilation.</p>
 *
 * <h2>Native Image Support:</h2>
 * <p>The processor automatically registers classes that require reflection
 * access during native image execution, ensuring that the API Sheriff
 * functionality works correctly in both JVM and native modes.</p>
 *
 * @author API Sheriff Team
 */
public class ApiSheriffProcessor {

    private static final String FEATURE = "api-sheriff";

    /**
     * Registers the API Sheriff feature with Quarkus.
     * This build step creates a feature item that identifies the API Sheriff
     * extension in the Quarkus runtime.
     *
     * @return FeatureBuildItem for API Sheriff
     */
    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Registers API Sheriff classes as CDI beans.
     * This build step ensures that all API Sheriff components are available
     * for dependency injection in the Quarkus application.
     *
     * @param additionalBeans producer for additional CDI beans
     */
    @BuildStep
    void addBeans(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(ApiSheriffProducer.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(ApiSheriff.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(ApiGatewayConfig.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(RateLimiter.class));
    }

    /**
     * Registers classes for reflection in native image.
     * This build step ensures that API Sheriff classes that require reflection
     * access are properly registered for GraalVM native image compilation.
     *
     * @param reflectiveClass producer for reflective class registration
     * @param combinedIndex the combined index of all classes
     */
    @BuildStep
    void addReflectiveClasses(BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
                              CombinedIndexBuildItem combinedIndex) {

        // Register main API Sheriff classes for reflection
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ApiSheriff.class)
                .constructors()
                .methods()
                .fields()
                .build());

        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ApiGatewayConfig.class)
                .constructors()
                .methods()
                .fields()
                .build());

        reflectiveClass.produce(ReflectiveClassBuildItem.builder(RateLimiter.class)
                .constructors()
                .methods()
                .fields()
                .build());

        // Register the producer class for CDI proxy generation
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ApiSheriffProducer.class)
                .constructors()
                .methods()
                .fields()
                .build());
    }


}