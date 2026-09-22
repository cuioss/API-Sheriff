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
package de.cuioss.sheriff.gateway.portal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The single definition of the portal template's data model — the contract every template, built-in
 * or operator-authored, renders against.
 * <p>
 * {@link #toMap()} produces a map whose keys are <em>always all present</em>, an absent value being
 * {@code null}, so a template's {@code {#if}} over an optional member never meets an unknown key under
 * strict rendering. The values are limited to {@code String}, {@code Boolean}, {@code Integer},
 * {@code List} and {@code Map}: no domain object reaches a template, which keeps rendering free of
 * reflection (native-image safe) and gives a template nothing to call beyond reading these values.
 * <pre>
 * title                        String   the configured portal title
 * apps[]                       List     the catalog, in catalog order
 *   .title / .description      String   entry title; description or null
 *   .entry                     String   the origin-relative link target
 * session.authenticated        Boolean  whether a live session was resolved
 * session.username             String   preferred_username, null when anonymous or absent
 * links.login / links.logout   String   login / logout link, null when not offered
 * notice                       String   a fixed-vocabulary notice ({@link PortalNotice}) or null
 * context_path                 String   the application context path
 * error                        Map      null outside an HTML error page
 *   .status                    Integer  the preserved HTTP status
 *   .title                     String   the fixed per-status title
 * </pre>
 * <p>
 * Immutable; thread-safe.
 *
 * @param title         the configured portal title
 * @param catalog       the resolved catalog
 * @param authenticated whether a live session was resolved for the request
 * @param username      the session's {@code preferred_username}, {@code null} when anonymous or when
 *                      the claim is absent; must be {@code null} when not {@code authenticated}
 * @param loginLink     the login link, {@code null} when no login is offered
 * @param logoutLink    the logout link, {@code null} when no logout is offered
 * @param notice        the recognised notice, {@code null} when none
 * @param contextPath   the application context path
 * @param errorStatus   the HTTP status of an HTML error page, {@code null} for the overview page
 * @param errorTitle    the fixed title of an HTML error page, {@code null} for the overview page
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record PortalPageModel(
String title,
PortalCatalog catalog,
boolean authenticated,
@Nullable String username,
@Nullable String loginLink,
@Nullable String logoutLink,
@Nullable PortalNotice notice,
String contextPath,
@Nullable Integer errorStatus,
@Nullable String errorTitle) {

    /** Top-level key: the configured portal title. */
    public static final String TITLE = "title";
    /** Top-level key: the catalog entries. */
    public static final String APPS = "apps";
    /** Top-level key: the session block. */
    public static final String SESSION = "session";
    /** Top-level key: the login/logout links block. */
    public static final String LINKS = "links";
    /** Top-level key: the recognised notice. */
    public static final String NOTICE = "notice";
    /** Top-level key: the application context path. */
    public static final String CONTEXT_PATH = "context_path";
    /** Top-level key: the error block, {@code null} outside an HTML error page. */
    public static final String ERROR = "error";

    /** Key of an app entry: its title. */
    public static final String APP_TITLE = "title";
    /** Key of an app entry: its description. */
    public static final String APP_DESCRIPTION = "description";
    /** Key of an app entry: its origin-relative link target. */
    public static final String APP_ENTRY = "entry";

    /** Key of the session block: whether a live session was resolved. */
    public static final String SESSION_AUTHENTICATED = "authenticated";
    /** Key of the session block: the {@code preferred_username}. */
    public static final String SESSION_USERNAME = "username";

    /** Key of the links block: the login link. */
    public static final String LINK_LOGIN = "login";
    /** Key of the links block: the logout link. */
    public static final String LINK_LOGOUT = "logout";

    /** Key of the error block: the preserved HTTP status. */
    public static final String ERROR_STATUS = "status";
    /** Key of the error block: the fixed per-status title. */
    public static final String ERROR_TITLE = "title";

    /**
     * Canonical constructor enforcing the model's invariants: the mandatory members are present, an
     * anonymous model carries no username, and the error status and title are declared together.
     *
     * @throws IllegalArgumentException if {@code username} is set without {@code authenticated}, or
     *                                  exactly one of {@code errorStatus} / {@code errorTitle} is set
     */
    public PortalPageModel {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(contextPath, "contextPath");
        if (!authenticated && username != null) {
            throw new IllegalArgumentException("an anonymous page model carries no username");
        }
        if ((errorStatus == null) != (errorTitle == null)) {
            throw new IllegalArgumentException("errorStatus and errorTitle are declared together");
        }
    }

    /**
     * @return the template data model: every contract key present, values limited to plain
     * {@code String} / {@code Boolean} / {@code Integer} / {@code List} / {@code Map}; unmodifiable
     */
    public Map<String, Object> toMap() {
        Map<String, @Nullable Object> model = new LinkedHashMap<>();
        model.put(TITLE, title);
        model.put(APPS, catalog.entries().stream().map(PortalPageModel::app).toList());
        model.put(SESSION, block(SESSION_AUTHENTICATED, authenticated, SESSION_USERNAME, username));
        model.put(LINKS, block(LINK_LOGIN, loginLink, LINK_LOGOUT, logoutLink));
        model.put(NOTICE, notice == null ? null : notice.wireValue());
        model.put(CONTEXT_PATH, contextPath);
        model.put(ERROR, errorStatus == null ? null : block(ERROR_STATUS, errorStatus, ERROR_TITLE, errorTitle));
        return unmodifiable(model);
    }

    private static Map<String, Object> app(PortalCatalog.Entry entry) {
        Map<String, @Nullable Object> app = new LinkedHashMap<>();
        app.put(APP_TITLE, entry.title());
        app.put(APP_DESCRIPTION, entry.description());
        app.put(APP_ENTRY, entry.entry());
        return unmodifiable(app);
    }

    private static Map<String, Object> block(String firstKey, @Nullable Object firstValue, String secondKey,
            @Nullable Object secondValue) {
        Map<String, @Nullable Object> block = new LinkedHashMap<>();
        block.put(firstKey, firstValue);
        block.put(secondKey, secondValue);
        return unmodifiable(block);
    }

    /**
     * Wraps a null-tolerant map for the template. The values are nullable by contract — an absent
     * member is a present key mapped to {@code null} — while the declared type stays the plain
     * {@code Map<String, Object>} the template engine consumes.
     */
    private static Map<String, Object> unmodifiable(Map<String, @Nullable Object> map) {
        return Collections.unmodifiableMap(map);
    }
}
