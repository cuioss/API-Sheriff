/**
 * @fileoverview Single target resolver for the API Sheriff benchmark lane.
 *
 * Every aspect script asks this module for its base URL instead of hard-coding a host, so one
 * script body measures whichever gateway the run targets. The CI baseline lane sets nothing and
 * gets API Sheriff; the on-demand comparison lane re-runs the same scripts with
 * `GATEWAY_TARGET=apisix` to produce the other side of the side-by-side.
 *
 * Both edges are reached by compose service name on the shared `api-sheriff` network and both
 * present the same mounted certificate, so the only thing that varies per target is the host.
 * Host-published ports (10443 / 10444) are deliberately NOT used: routing the load generator
 * through the host's port-forwarding path would measure Docker's proxy alongside the gateway.
 *
 * An unknown target is fatal rather than defaulted. Silently falling back to API Sheriff would
 * label an API Sheriff run as the other gateway in `gateway_target`, and a mislabelled comparison
 * artifact is worse than a failed run -- it is wrong data that reads as correct.
 *
 * One resolver deliberately sits OUTSIDE that per-target mapping: {@link passthroughEmptyUrl}. It
 * addresses the dedicated `api-sheriff-passthrough-empty` gateway instance, which exists only to
 * run one API Sheriff configuration (a `gateway.yaml` declaring no `tls.passthrough_sni`) against
 * another. Routing it through {@link gatewayTarget} would make `GATEWAY_TARGET=apisix` silently
 * point an API-Sheriff-versus-API-Sheriff comparison at APISIX, which has no such instance and no
 * such configuration -- the run would still produce a summary, and that summary would be nonsense.
 * The exclusion is the point, not an oversight.
 *
 * The gateway's two CONTEXT PATHS are resolved here as well, so no benchmark script restates
 * either literal. The application context path (`HTTP_ROOT_PATH`, default `/`) is applied inside
 * {@link targetUrl} and {@link wsUrl} ONLY -- never inside {@link baseUrl}, which stays a bare
 * `scheme://host:port` so its remaining consumers keep working: the cookie-jar lookup in
 * `session_mediated.js` scopes cookies by ORIGIN rather than by path, and {@link grpcAddress}
 * merely strips the scheme off it. Applying the prefix one level lower would corrupt both.
 *
 * The management context path (`MANAGEMENT_ROOT_PATH`, default `/q`) is applied inside
 * {@link managementUrl}, which addresses the Quarkus management port on the API Sheriff instance
 * and therefore resolves independently of {@link gatewayTarget}: the health benchmarks are
 * excluded from the cross-gateway comparison lane because APISIX exposes no management interface
 * at all, so there is nothing to point them at.
 *
 * Two resolvers stay deliberately UNPREFIXED, and neither is an omission.
 * {@link passthroughEmptyUrl} addresses an L4 TLS relay: no HTTP router path can move that target
 * because no HTTP router ever sees the bytes. {@link grpcAddress} returns a bare `host:port` with
 * no path component at all, because a gRPC client derives its `:path` pseudo-header from the
 * loaded proto descriptor rather than from a URL.
 */

/**
 * Base URL per supported gateway target, keyed by the label recorded in `gateway_target`.
 *
 * @type {Object<string, string>}
 */
const BASE_URLS = {
    'api-sheriff': 'https://api-sheriff:8443',
    apisix: 'https://apisix:8443',
};

/** The target assumed when `GATEWAY_TARGET` is unset, keeping the CI lane plumbing-free. */
const DEFAULT_TARGET = 'api-sheriff';

/**
 * Base URL of the dedicated gateway instance whose `gateway.yaml` declares no
 * `tls.passthrough_sni`, reached by compose service name on the shared `api-sheriff` network like
 * every other edge. It is a second API Sheriff process, not a second gateway product, so it is
 * absent from {@link BASE_URLS} and unreachable through {@link gatewayTarget} -- see the module
 * `@fileoverview` for why that separation is deliberate.
 *
 * @type {string}
 */
export const PASSTHROUGH_EMPTY_BASE_URL = 'https://api-sheriff-passthrough-empty:8443';

/**
 * Scheme, host and port of the Quarkus MANAGEMENT interface on the API Sheriff instance. Quarkus'
 * management interface has exactly one port and activating TLS converted it to HTTPS, so this is
 * `https://` on 9000 rather than the plain-HTTP surface it once was.
 *
 * It is absent from {@link BASE_URLS} on purpose: APISIX exposes no management interface, so
 * routing it through {@link gatewayTarget} would aim the health benchmarks at a port that does not
 * answer whenever `GATEWAY_TARGET=apisix`.
 *
 * @type {string}
 */
const MANAGEMENT_BASE_URL = 'https://api-sheriff:9000';

/**
 * Drops a single trailing slash so a base URL concatenates with a leading-slash path exactly once.
 *
 * @param {string} url the base URL to normalize
 * @returns {string} the URL without a trailing slash
 */
function withoutTrailingSlash(url) {
    return url.endsWith('/') ? url.slice(0, -1) : url;
}

/**
 * Normalizes a context path into a segment that concatenates cleanly between a base URL and a
 * leading-slash route path: the EMPTY STRING for the root context path `/`, and otherwise a
 * leading-slash path carrying no trailing slash. That normalization is what lets the default
 * (`/` for the application, `/q` for management) compose byte-identically to the literals these
 * resolvers replaced.
 *
 * The WHOLE trailing run of slashes is stripped, not just one, because the contract above is about
 * the returned segment rather than about how many slashes were supplied: `/custom//` composes into
 * a doubled slash before the route if only the last one is dropped. It deliberately does NOT reuse
 * {@link withoutTrailingSlash}, whose single-slash semantics are correct for the base URLs it
 * normalizes elsewhere in this module.
 *
 * `/` and `/q` are unaffected by the widening -- both already normalized to `''` and `/q` -- so this
 * is not a fourth instance of the root-context-path class of defect this module has already been
 * bitten by; it is the multi-slash case that was never covered.
 *
 * @param {string|undefined} raw the configured context path, or `undefined`/empty when unset
 * @param {string} fallback the context path assumed when nothing is configured
 * @returns {string} the concatenable segment, possibly empty
 */
function rootPathSegment(raw, fallback) {
    const resolved = raw === undefined || raw === '' ? fallback : raw;
    const absolute = resolved.startsWith('/') ? resolved : `/${resolved}`;
    return absolute.replace(/\/+$/, '');
}

/**
 * The gateway's MANAGEMENT context path (`quarkus.management.root-path`), as the concatenable
 * segment {@link managementUrl} composes with. Defaults to the shipped `/q`.
 *
 * @type {string}
 */
const MANAGEMENT_ROOT_PATH = rootPathSegment(__ENV.MANAGEMENT_ROOT_PATH, '/q');

/**
 * The gateway's APPLICATION context path (`quarkus.http.root-path`), as the concatenable segment
 * {@link targetUrl} and {@link wsUrl} compose with. Defaults to the shipped `/`, which normalizes
 * to the empty string and therefore adds nothing to a route path.
 *
 * @type {string}
 */
const APPLICATION_ROOT_PATH = rootPathSegment(__ENV.HTTP_ROOT_PATH, '/');

/**
 * Resolves the gateway a run is taken against, validated against the supported set.
 *
 * @returns {string} the gateway target label recorded in the summary
 * @throws {Error} when `GATEWAY_TARGET` is set to an unsupported value
 */
export function gatewayTarget() {
    const raw = __ENV.GATEWAY_TARGET;
    if (raw === undefined || raw === '') {
        return DEFAULT_TARGET;
    }
    if (!Object.prototype.hasOwnProperty.call(BASE_URLS, raw)) {
        throw new Error(
            `GATEWAY_TARGET must be one of ${Object.keys(BASE_URLS).join(', ')}, got "${raw}"`);
    }
    return raw;
}

/**
 * Resolves the base URL of the targeted gateway.
 *
 * `TARGET_BASE_URL` overrides the mapping for a run against an edge that is not a compose
 * service (a host-published port, or a gateway deployed elsewhere). The override changes only
 * where the requests go -- `gateway_target` still labels the run -- so an override aimed at the
 * wrong edge is visible in the artifact rather than hidden by it.
 *
 * @returns {string} the base URL, without a trailing slash
 */
export function baseUrl() {
    const override = __ENV.TARGET_BASE_URL;
    const resolved = override === undefined || override === '' ? BASE_URLS[gatewayTarget()] : override;
    return withoutTrailingSlash(resolved);
}

/**
 * Builds an absolute URL for a route path on the targeted gateway, beneath the APPLICATION context
 * path. The prefix is applied HERE rather than in {@link baseUrl} so that {@link baseUrl}'s other
 * consumers -- the origin-scoped cookie-jar lookup and {@link grpcAddress} -- keep seeing a bare
 * `scheme://host:port`.
 *
 * @param {string} path the route path, with a leading slash (e.g. `/proxy/static`)
 * @returns {string} the absolute URL to request
 */
export function targetUrl(path) {
    return `${baseUrl()}${APPLICATION_ROOT_PATH}${path}`;
}

/**
 * Builds an absolute URL for an endpoint on the Quarkus MANAGEMENT interface, beneath the
 * management context path (`MANAGEMENT_ROOT_PATH`, default `/q`).
 *
 * It composes host, port and context path exactly as {@link targetUrl} composes the application
 * ones, with one deliberate difference: the host is {@link MANAGEMENT_BASE_URL} rather than the
 * per-target mapping, so this resolver returns the same management endpoint whichever value
 * `GATEWAY_TARGET` carries. The health benchmarks that call it are excluded from the cross-gateway
 * comparison lane, because APISIX exposes no management interface to compare against.
 *
 * @param {string} path the endpoint path beneath the context path, with a leading slash
 *   (e.g. `/health`)
 * @returns {string} the absolute URL to request
 */
export function managementUrl(path) {
    return `${MANAGEMENT_BASE_URL}${MANAGEMENT_ROOT_PATH}${path}`;
}

/**
 * Builds an absolute URL for a route path on the no-`passthrough_sni` gateway instance.
 *
 * `PASSTHROUGH_EMPTY_BASE_URL` overrides {@link PASSTHROUGH_EMPTY_BASE_URL} for a run against an
 * edge that is not a compose service, in the same shape as `TARGET_BASE_URL` overrides the
 * per-target mapping in {@link baseUrl}. It resolves independently of `GATEWAY_TARGET`, so this
 * function returns the same host whichever gateway the surrounding run measures.
 *
 * No context path is applied, deliberately: this instance is reached as an L4 TLS passthrough
 * relay, so no HTTP router ever sees the bytes and no HTTP router path could move the target.
 *
 * @param {string} path the route path, with a leading slash (e.g. `/proxy/static`)
 * @returns {string} the absolute URL to request
 */
export function passthroughEmptyUrl(path) {
    const override = __ENV.PASSTHROUGH_EMPTY_BASE_URL;
    const resolved = override === undefined || override === '' ? PASSTHROUGH_EMPTY_BASE_URL : override;
    return `${withoutTrailingSlash(resolved)}${path}`;
}

/**
 * Builds an absolute WebSocket URL for a route path on the targeted gateway, reusing the same
 * host/target resolution and the same APPLICATION context path as {@link targetUrl} but on the
 * `wss://` scheme the WebSocket upgrade requires.
 *
 * @param {string} path the WebSocket route path, with a leading slash (e.g. `/ws/echo`)
 * @returns {string} the absolute `wss://` URL to open the socket against
 */
export function wsUrl(path) {
    const wsBase = baseUrl().replace(/^https:/, 'wss:').replace(/^http:/, 'ws:');
    return `${wsBase}${APPLICATION_ROOT_PATH}${path}`;
}

/**
 * Resolves the `host:port` address a k6 gRPC client dials the targeted gateway on — the base URL
 * with its scheme stripped. TLS is negotiated by the client (`plaintext: false`); the gateway
 * forces HTTP/2 to the upstream, so the client speaks ordinary gRPC over TLS to the edge.
 *
 * No context path is applied, deliberately: this is a bare `host:port` carrying no path component,
 * because a gRPC client derives its `:path` pseudo-header from the loaded proto descriptor rather
 * than from a URL.
 *
 * @returns {string} the `host:port` address (e.g. `api-sheriff:8443`)
 */
export function grpcAddress() {
    return baseUrl().replace(/^https?:\/\//, '');
}
