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
package de.cuioss.sheriff.gateway;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.config.boot.ConfigBootPipeline;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.load.DefaultedPlaceholder;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;

/**
 * The offline configuration check behind the pre-boot {@value #FLAG} flag: it runs the boot's own
 * configuration pipeline over a configuration directory and exits with a status code a CI pipeline
 * can act on, without starting the gateway.
 * <p>
 * {@link ApiSheriffApplication#main(String[])} recognises the token {@value #FLAG} and hands the
 * command line to {@link #run(String[], PrintStream, PrintStream, EnvSecretResolver)} <em>before</em>
 * Quarkus is started, exactly like the container health probe (ADR-0039). No CDI container, listener,
 * identity-provider client or bind exists on this path.
 *
 * <h2>Single-sourced verdict</h2>
 * <p>
 * The check is not a second validator: it calls {@link ConfigBootPipeline} — the same seam the boot
 * calls — so a configuration the boot refuses on a pipeline stage is reported here with the same
 * file, pointer and message. The one framework value the pipeline needs, the request-body ceiling
 * {@value ConfigBootPipeline#FRAMEWORK_BODY_LIMIT_KEY}, is a runtime framework key that is not part of
 * the configuration directory, so it is passed as unknown and reported as not checked.
 *
 * <h2>Report</h2>
 * <p>
 * The report goes to the {@code out} stream, in this order:
 * <ol>
 * <li>one {@code INVALID <file> [<pointer>]: <message>} line per violation, in pipeline order;</li>
 * <li>one {@code DEFAULTED <file> [<pointer>]: ${<NAME>} unset, in-file default applied} line per
 * {@code ${NAME:-default}} placeholder that fell back to its in-file default — the variable name and
 * location only, never the default literal or any value;</li>
 * <li>one {@code NOT CHECKED: <name> - <reason>} line per boot refusal this check cannot evaluate:
 * the pipeline's own not-checked keys, followed by the fixed catalogue of the boot refusals raised
 * outside the pipeline, printed on every run so that none is skipped silently;</li>
 * <li>one {@code POLICY:} line stating that {@code ${VAR}} placeholders were resolved from the
 * environment of this process;</li>
 * <li>a final {@code RESULT: violations=<n> defaulted=<n> not-checked=<n>} line.</li>
 * </ol>
 * Usage errors and unreadable input are reported as one line on the {@code err} stream.
 *
 * <h2>Exit codes</h2>
 * <ul>
 * <li>{@value #EXIT_VALID} — no violation; {@code DEFAULTED} and {@code NOT CHECKED} lines are
 * informational and never change the exit code;</li>
 * <li>{@value #EXIT_INVALID} — at least one {@code INVALID} line was printed;</li>
 * <li>{@value #EXIT_USAGE} — the directory argument is missing, the directory does not exist, is not
 * a directory or is unreadable, or its {@code gateway.yaml} is missing or unreadable.</li>
 * </ul>
 *
 * <h2>Placeholders and secrets</h2>
 * <p>
 * Placeholders are substituted through the supplied {@link EnvSecretResolver} — in production the
 * process environment — exactly as the boot substitutes them, so the verdict holds for the
 * environment of this run. An unset bare {@code ${VAR}} is a violation, never treated as valid. The
 * report echoes no configuration value beyond what the pipeline's own violation messages carry, and
 * never a secret.
 * <p>
 * <strong>Why the report is written to a {@link PrintStream}.</strong> This is program output on a
 * path where the logging manager is not installed, so {@code CuiLogger} is not a usable sink and no
 * {@code LogRecord} is introduced for it — the same reasoning {@link HealthProbe} documents. Log
 * lines the shared pipeline code emits may still appear on standard error; they never change the
 * report or the exit code.
 * <p>
 * <strong>Why this is its own class rather than a branch on the entry point.</strong> The entry
 * point either terminates the JVM or hands control to Quarkus and is therefore untestable; this class
 * takes its streams and resolver as arguments and returns its exit code, so all of its behaviour is
 * measured.
 * <p>
 * <strong>Thread safety:</strong> this class holds no state; concurrent invocations are safe as long
 * as they do not share output streams.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ConfigValidationCommand {

    /** The single token {@link ApiSheriffApplication#main(String[])} answers as an offline check. */
    static final String FLAG = "--validate-config";

    /** Exit code of a run that printed no violation. */
    static final int EXIT_VALID = 0;

    /** Exit code of a run that printed at least one violation. */
    static final int EXIT_INVALID = 1;

    /** Exit code of a usage error or unreadable input. */
    static final int EXIT_USAGE = 2;

    private static final String GATEWAY_FILE = "gateway.yaml";
    private static final String ERROR_PREFIX = "validate-config: ";

    /** Reasons for the keys the pipeline itself reports as not checked. */
    private static final Map<String, String> PIPELINE_NOT_CHECKED_REASONS = Map.of(
            ConfigBootPipeline.FRAMEWORK_BODY_LIMIT_KEY,
            "per-route max_body_bytes against the framework request-body ceiling: a runtime framework key"
                    + " outside the configuration directory; checked at boot (ApiSheriff-200)");

    private static final String UNKNOWN_NOT_CHECKED_REASON = "not evaluable without the running gateway";

    /**
     * The fixed catalogue of boot refusals raised outside the pipeline. Each reason names its refusals
     * individually, so the four that depend on {@code gateway.yaml} alone — the
     * {@code jwks.tls_profile} / {@code jwks_verify_hostname: false} collision, {@code tls.mtls.enabled}
     * without {@code client_ca}, the JWKS source shape and the {@code oidc_tls_profile} /
     * {@code oidc_verify_hostname: false} collision — appear by name on every run.
     */
    private static final List<NotChecked> OUT_OF_PIPELINE_REFUSALS = List.of(
            new NotChecked("server TLS declaration",
                    "listener key material against quarkus.http.insecure-requests, the TLS bucket name,"
                            + " mTLS / passthrough coherence: deployment keys, checked at boot"),
            new NotChecked("TLS listener settings",
                    "tls.min_version, tls.cipher_suites against the running JDK, tls.alpn:"
                            + " JDK-dependent listener wiring, checked at boot"),
            new NotChecked("mTLS listener",
                    "tls.mtls.enabled without client_ca: listener wiring, checked at boot"),
            new NotChecked("SNI front listener",
                    "the tls.passthrough_sni front-listener bind: a bind, not a configuration verdict"),
            new NotChecked("token validation",
                    "token_validation present, the JWKS source shape (source / url / host / file,"
                            + " unsupported source), jwks.tls_profile with jwks_verify_hostname: false,"
                            + " the file-sourced JWKS load: eager assembly, checked at boot"),
            new NotChecked("JWKS / egress trust profiles",
                    "a named trust profile that is unbound, anchor-free or trust-all:"
                            + " deployment quarkus.tls.* material, checked at boot"),
            new NotChecked("BFF runtime",
                    "client-authentication / sender-constraint key files, cookie key material,"
                            + " oidc_tls_profile with oidc_verify_hostname: false: deployment key material,"
                            + " checked at boot"),
            new NotChecked("portal template",
                    "the operator portal.html refusal: the template file is deployment material,"
                            + " checked at boot"));

    private static final String POLICY_LINE = "POLICY: ${VAR} placeholders were resolved from the environment"
            + " of this process, so a variable the target deployment sets differently is validated with the"
            + " value of this run; an unset bare ${VAR} appears above as INVALID and an unset ${VAR:-default}"
            + " appears above as DEFAULTED";

    private ConfigValidationCommand() {
        // utility
    }

    /**
     * Reports whether the command line requests the offline configuration check.
     * <p>
     * The match is exact against a whole token: a prefix, a substring, or a differently-spelled flag
     * does not match, so an ordinary application argument can never be mistaken for the request.
     *
     * @param args the raw command line
     * @return {@code true} when {@code args} contains the token {@value #FLAG}
     * @since 1.0
     */
    public static boolean isRequested(String[] args) {
        return Arrays.asList(args).contains(FLAG);
    }

    /**
     * Runs the offline configuration check over the directory named by the token after
     * {@value #FLAG}, writes the report and returns the exit code.
     *
     * @param args     the raw command line; the configuration directory is the token following the
     *                 first {@value #FLAG}
     * @param out      the stream receiving the report
     * @param err      the stream receiving a usage or unreadable-input error
     * @param resolver the placeholder substitution engine — in production the process environment
     * @return {@value #EXIT_VALID} when no violation was printed, {@value #EXIT_INVALID} when at least
     *         one was, {@value #EXIT_USAGE} for a usage error or unreadable input
     * @since 1.0
     */
    public static int run(String[] args, PrintStream out, PrintStream err, EnvSecretResolver resolver) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Objects.requireNonNull(resolver, "resolver");

        int flagIndex = Arrays.asList(args).indexOf(FLAG);
        if (flagIndex < 0 || flagIndex + 1 >= args.length) {
            emit(err, ERROR_PREFIX + "missing configuration directory; usage: " + FLAG + " <dir>");
            return EXIT_USAGE;
        }
        String argument = args[flagIndex + 1];
        Path configDir;
        try {
            configDir = Path.of(argument);
        } catch (InvalidPathException e) {
            emit(err, ERROR_PREFIX + "'" + argument + "' is not a valid path");
            return EXIT_USAGE;
        }
        Optional<String> inputError = inputError(configDir);
        if (inputError.isPresent()) {
            emit(err, ERROR_PREFIX + inputError.get());
            return EXIT_USAGE;
        }

        ConfigBootPipeline.Outcome outcome = new ConfigBootPipeline(resolver).run(configDir, null);
        List<NotChecked> notChecked = notChecked(outcome.notChecked());
        for (ConfigError violation : outcome.violations()) {
            emit(out, "INVALID " + violation.file() + " [" + violation.pointer() + "]: " + violation.message());
        }
        for (DefaultedPlaceholder defaulted : outcome.defaulted()) {
            emit(out, "DEFAULTED " + defaulted.file() + " [" + defaulted.pointer() + "]: ${"
                    + defaulted.variableName() + "} unset, in-file default applied");
        }
        for (NotChecked entry : notChecked) {
            emit(out, "NOT CHECKED: " + entry.name() + " - " + entry.reason());
        }
        emit(out, POLICY_LINE);
        emit(out, "RESULT: violations=" + outcome.violations().size() + " defaulted=" + outcome.defaulted().size()
                + " not-checked=" + notChecked.size());
        return outcome.violations().isEmpty() ? EXIT_VALID : EXIT_INVALID;
    }

    /**
     * Checks that the configuration directory and its {@code gateway.yaml} can be read.
     *
     * @param configDir the configuration directory named on the command line
     * @return the error description when the input is unusable, otherwise empty
     */
    private static Optional<String> inputError(Path configDir) {
        if (!Files.exists(configDir)) {
            return Optional.of("configuration directory '" + configDir + "' does not exist");
        }
        if (!Files.isDirectory(configDir)) {
            return Optional.of("'" + configDir + "' is not a directory");
        }
        if (!Files.isReadable(configDir)) {
            return Optional.of("configuration directory '" + configDir + "' is not readable");
        }
        Path gatewayFile = configDir.resolve(GATEWAY_FILE);
        if (!Files.isRegularFile(gatewayFile)) {
            return Optional.of("configuration directory '" + configDir + "' contains no " + GATEWAY_FILE);
        }
        if (!Files.isReadable(gatewayFile)) {
            return Optional.of("'" + gatewayFile + "' is not readable");
        }
        return Optional.empty();
    }

    /**
     * Combines the pipeline's not-checked keys with the fixed out-of-pipeline catalogue.
     *
     * @param pipelineNotChecked the keys the pipeline reported as not checked, in its order
     * @return every not-checked entry, the pipeline's first
     */
    private static List<NotChecked> notChecked(List<String> pipelineNotChecked) {
        List<NotChecked> entries = new ArrayList<>(pipelineNotChecked.size() + OUT_OF_PIPELINE_REFUSALS.size());
        for (String key : pipelineNotChecked) {
            entries.add(new NotChecked(key, PIPELINE_NOT_CHECKED_REASONS.getOrDefault(key, UNKNOWN_NOT_CHECKED_REASON)));
        }
        entries.addAll(OUT_OF_PIPELINE_REFUSALS);
        return entries;
    }

    /**
     * Writes one report or error line.
     *
     * @param stream the target stream
     * @param line   the line to write
     */
    private static void emit(PrintStream stream, String line) {
        // cui-rewrite:disable CuiLoggerStandardsRecipe
        stream.println(line); // NOSONAR java:S106 pre-boot offline check: no logging manager yet; the report is program output
    }

    /**
     * A boot refusal this check cannot evaluate, with the reason it is not checked.
     *
     * @param name   the refusal family or key
     * @param reason the refusals the entry covers and why they are not evaluated offline
     */
    private record NotChecked(String name, String reason) {
    }
}
