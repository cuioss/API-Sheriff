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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PortalPageModel}: every contract key is present for the anonymous, authenticated
 * and error shapes, absent values are present keys mapped to {@code null}, and only the contract's
 * plain value types reach the map.
 */
@EnableGeneratorController
@DisplayName("PortalPageModel")
class PortalPageModelTest {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("title", "apps", "session", "links", "notice",
            "context_path", "error");
    private static final Set<String> APP_KEYS = Set.of("title", "description", "entry");
    private static final Set<String> SESSION_KEYS = Set.of("authenticated", "username");
    private static final Set<String> LINK_KEYS = Set.of("login", "logout");
    private static final Set<String> ERROR_KEYS = Set.of("status", "title");

    private static final Set<Class<?>> CONTRACT_VALUE_TYPES = Set.of(String.class, Boolean.class, Integer.class);

    private static PortalCatalog catalog() {
        return new PortalCatalog(List.of(
                new PortalCatalog.Entry("Orders", "Order management", "/orders/", 1),
                new PortalCatalog.Entry("Reports", null, "/reports/", null)));
    }

    private static PortalPageModel.PortalPageModelBuilder base() {
        return PortalPageModel.builder()
                .title(Generators.nonBlankStrings().next())
                .catalog(catalog())
                .contextPath("/gw");
    }

    private static Map<?, ?> block(Map<String, Object> model, String key) {
        return assertInstanceOf(Map.class, model.get(key), key);
    }

    private static List<?> apps(Map<String, Object> model) {
        return assertInstanceOf(List.class, model.get(PortalPageModel.KEY_APPS));
    }

    private static Map<?, ?> app(List<?> apps, int index) {
        return assertInstanceOf(Map.class, apps.get(index));
    }

    @Test
    @DisplayName("An anonymous overview carries every key, with the absent values mapped to null")
    void anonymousModelCarriesEveryKey() {
        PortalPageModel pageModel = base().build();

        Map<String, Object> model = pageModel.toMap();

        assertAll(
                () -> assertEquals(TOP_LEVEL_KEYS, model.keySet()),
                () -> assertEquals(pageModel.title(), model.get("title")),
                () -> assertEquals("/gw", model.get("context_path")),
                () -> assertNull(model.get("notice")),
                () -> assertNull(model.get("error")),
                () -> assertEquals(SESSION_KEYS, block(model, "session").keySet()),
                () -> assertEquals(Boolean.FALSE, block(model, "session").get("authenticated")),
                () -> assertNull(block(model, "session").get("username")),
                () -> assertEquals(LINK_KEYS, block(model, "links").keySet()),
                () -> assertNull(block(model, "links").get("login")),
                () -> assertNull(block(model, "links").get("logout")));
    }

    @Test
    @DisplayName("An authenticated overview carries the username, links and notice")
    void authenticatedModelCarriesSessionAndLinks() {
        String username = Generators.nonBlankStrings().next();

        Map<String, Object> model = base()
                .authenticated(true)
                .username(username)
                .loginLink("/auth/login?returnUrl=%2Fgw%2F")
                .logoutLink("/auth/logout")
                .notice(PortalNotice.LOGGED_OUT)
                .build()
                .toMap();

        assertAll(
                () -> assertEquals(TOP_LEVEL_KEYS, model.keySet()),
                () -> assertEquals(Boolean.TRUE, block(model, "session").get("authenticated")),
                () -> assertEquals(username, block(model, "session").get("username")),
                () -> assertEquals("/auth/login?returnUrl=%2Fgw%2F", block(model, "links").get("login")),
                () -> assertEquals("/auth/logout", block(model, "links").get("logout")),
                () -> assertEquals("logged-out", model.get("notice")));
    }

    @Test
    @DisplayName("An error page carries the error block with status and title")
    void errorModelCarriesErrorBlock() {
        Map<String, Object> model = base().errorStatus(404).errorTitle("Not Found").build().toMap();

        assertAll(
                () -> assertEquals(TOP_LEVEL_KEYS, model.keySet()),
                () -> assertEquals(ERROR_KEYS, block(model, "error").keySet()),
                () -> assertEquals(404, block(model, "error").get("status")),
                () -> assertEquals("Not Found", block(model, "error").get("title")));
    }

    @Test
    @DisplayName("Lists the catalog entries in catalog order with every app key present")
    void listsCatalogEntries() {
        List<?> apps = apps(base().build().toMap());

        assertAll(
                () -> assertEquals(2, apps.size()),
                () -> assertEquals(APP_KEYS, app(apps, 0).keySet()),
                () -> assertEquals(APP_KEYS, app(apps, 1).keySet()),
                () -> assertEquals("Orders", app(apps, 0).get("title")),
                () -> assertEquals("Reports", app(apps, 1).get("title")),
                () -> assertEquals("/orders/", app(apps, 0).get("entry")),
                () -> assertNull(app(apps, 1).get("description")));
    }

    @Test
    @DisplayName("Only String, Boolean, Integer, List and Map values reach the model — no RawString")
    void onlyContractTypesReachTheModel() {
        Map<String, Object> model = base()
                .authenticated(true)
                .username("alice")
                .loginLink("/in")
                .logoutLink("/out")
                .notice(PortalNotice.LOGGED_OUT)
                .errorStatus(503)
                .errorTitle("Service Unavailable")
                .build()
                .toMap();

        List<String> strays = new ArrayList<>();
        collectNonContractValues("", model, strays);

        assertTrue(strays.isEmpty(), () -> "non-contract value types in the model: " + strays);
    }

    @Test
    @DisplayName("The model map and its nested blocks are unmodifiable")
    void modelIsUnmodifiable() {
        Map<String, Object> model = base().build().toMap();
        Map<?, ?> session = block(model, "session");
        List<?> apps = apps(model);

        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, () -> model.put("extra", "x")),
                () -> assertThrows(UnsupportedOperationException.class, session::clear),
                () -> assertThrows(UnsupportedOperationException.class, apps::clear));
    }

    @Test
    @DisplayName("Refuses a username on an anonymous model and a half-declared error block")
    void refusesInconsistentModels() {
        PortalPageModel.PortalPageModelBuilder anonymousWithUsername = base().username("alice");
        PortalPageModel.PortalPageModelBuilder statusWithoutTitle = base().errorStatus(404);
        PortalPageModel.PortalPageModelBuilder titleWithoutStatus = base().errorTitle("Not Found");

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, anonymousWithUsername::build),
                () -> assertThrows(IllegalArgumentException.class, statusWithoutTitle::build),
                () -> assertThrows(IllegalArgumentException.class, titleWithoutStatus::build));
    }

    @Test
    @DisplayName("Requires the title, the catalog and the context path")
    void requiresMandatoryMembers() {
        PortalPageModel.PortalPageModelBuilder withoutTitle = PortalPageModel.builder()
                .catalog(catalog()).contextPath("/");
        PortalPageModel.PortalPageModelBuilder withoutCatalog = PortalPageModel.builder()
                .title("Portal").contextPath("/");
        PortalPageModel.PortalPageModelBuilder withoutContextPath = PortalPageModel.builder()
                .title("Portal").catalog(catalog());

        assertAll(
                () -> assertThrows(NullPointerException.class, withoutTitle::build),
                () -> assertThrows(NullPointerException.class, withoutCatalog::build),
                () -> assertThrows(NullPointerException.class, withoutContextPath::build));
    }

    private static void collectNonContractValues(String path, Object value, List<String> strays) {
        switch (value) {
            case Map<?, ?> map -> map.forEach((key, nested) -> {
                if (nested != null) {
                    collectNonContractValues(path + "." + key, nested, strays);
                }
            });
            case List<?> list -> list.forEach(nested -> collectNonContractValues(path + "[]", nested, strays));
            default -> {
                if (!CONTRACT_VALUE_TYPES.contains(value.getClass())) {
                    strays.add(path + "=" + value.getClass().getName());
                }
            }
        }
    }
}
