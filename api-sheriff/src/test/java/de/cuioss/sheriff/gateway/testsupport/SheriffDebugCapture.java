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
package de.cuioss.sheriff.gateway.testsupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;


import de.cuioss.test.juli.TestLoggerFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Makes the test log handler capture the {@code DEBUG} records of the gateway and of the token
 * library, which {@code @EnableTestLogger(rootLevel = TestLogLevel.DEBUG)} alone does not.
 *
 * <h2>Why the root level is not enough</h2>
 * The shipped {@code application.properties} declares
 * {@code quarkus.log.category."de.cuioss.sheriff".level=INFO}. The Quarkus JUnit artifacts on the
 * test classpath switch on JUnit's extension auto-detection and register
 * {@code io.quarkus.test.config.LoggingSetupExtension}, which applies the Quarkus logging
 * configuration to <em>every</em> test class of this module — a plain JUnit test included. The
 * logger {@value #CATEGORY} therefore carries the level {@code INFO} of its own, and a level of its
 * own outranks the root level for that logger and for everything below it. With the root at
 * {@code DEBUG} a record of {@code org.example} is captured and a record of
 * {@code de.cuioss.sheriff.gateway} or {@code de.cuioss.sheriff.token} is not, so an assertion of the
 * shape "no captured record carries the secret" examines no {@code DEBUG} line of the code under
 * test.
 *
 * <h2>What this does</h2>
 * Before each test it sets the level of {@value #CATEGORY} to {@code DEBUG}, and after each test it
 * puts back the level it found. It lifts exactly the logger the configuration pins, so every logger
 * below it follows without being named; a class that starts logging later is covered as well.
 *
 * <h2>The control</h2>
 * A disclosure assertion calls {@link #assertDebugIsCaptured(Class...)} before it reads the captured
 * records. That emits one {@code DEBUG} record through each named logger and fails unless the
 * handler holds it, so the absence the assertion goes on to state was examined at {@code DEBUG}.
 *
 * <p>Use it together with {@code @EnableTestLogger(rootLevel = TestLogLevel.DEBUG)}: that annotation
 * installs the handler and opens it for {@code DEBUG}; this extension removes the one override
 * below it.
 */
public final class SheriffDebugCapture implements BeforeEachCallback, AfterEachCallback {

    /** The logger category the shipped configuration pins to {@code INFO}. */
    public static final String CATEGORY = "de.cuioss.sheriff";

    /** Held strongly, so the level set on it cannot be lost with a collected logger. */
    private static final Logger CATEGORY_LOGGER = Logger.getLogger(CATEGORY);

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(SheriffDebugCapture.class);
    private static final String LEVEL_BEFORE = "level-before";
    private static final AtomicLong CONTROL_SEQUENCE = new AtomicLong();

    /** The level the category carried before a test; {@code null} for a logger that inherited its level. */
    private record LevelBefore(@Nullable Level level) {
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(NAMESPACE).put(LEVEL_BEFORE, new LevelBefore(CATEGORY_LOGGER.getLevel()));
        CATEGORY_LOGGER.setLevel(Level.FINE);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        LevelBefore before = context.getStore(NAMESPACE).remove(LEVEL_BEFORE, LevelBefore.class);
        if (before != null) {
            CATEGORY_LOGGER.setLevel(before.level());
        }
    }

    /**
     * Emits one {@code DEBUG} record through the logger of each class and asserts that the test
     * handler captured it — the control of an assertion that goes on to state what the captured
     * records do <em>not</em> contain.
     *
     * @param loggers the classes whose loggers the code under test writes to
     */
    public static void assertDebugIsCaptured(Class<?>... loggers) {
        for (Class<?> type : loggers) {
            String name = type.getName();
            String marker = "debug capture control " + CONTROL_SEQUENCE.incrementAndGet();
            Logger.getLogger(name).log(Level.FINE, marker);
            assertTrue(TestLoggerFactory.getTestHandler().getRecords().stream()
                            .anyMatch(captured -> Level.FINE.equals(captured.getLevel())
                                    && name.equals(captured.getLoggerName()) && marker.equals(captured.getMessage())),
                    "a DEBUG record of " + name + " is not captured, so the absence of a value in the captured "
                            + "records would say nothing about the DEBUG output of that logger");
        }
    }

    /**
     * @param captured a captured log record
     * @return its message followed by the message of every throwable it chains
     */
    public static String rendered(LogRecord captured) {
        StringBuilder rendered = new StringBuilder(String.valueOf(captured.getMessage()));
        for (Throwable thrown = captured.getThrown(); thrown != null; thrown = thrown.getCause()) {
            rendered.append('\n').append(thrown.getMessage());
        }
        return rendered.toString();
    }
}
