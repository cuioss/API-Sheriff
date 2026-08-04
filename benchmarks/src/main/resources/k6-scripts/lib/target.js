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
 * Drops a single trailing slash so a base URL concatenates with a leading-slash path exactly once.
 *
 * @param {string} url the base URL to normalize
 * @returns {string} the URL without a trailing slash
 */
function withoutTrailingSlash(url) {
    return url.endsWith('/') ? url.slice(0, -1) : url;
}

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
 * Builds an absolute URL for a route path on the targeted gateway.
 *
 * @param {string} path the route path, with a leading slash (e.g. `/proxy/static`)
 * @returns {string} the absolute URL to request
 */
export function targetUrl(path) {
    return `${baseUrl()}${path}`;
}

/**
 * Builds an absolute URL for a route path on the no-`passthrough_sni` gateway instance.
 *
 * `PASSTHROUGH_EMPTY_BASE_URL` overrides {@link PASSTHROUGH_EMPTY_BASE_URL} for a run against an
 * edge that is not a compose service, in the same shape as `TARGET_BASE_URL` overrides the
 * per-target mapping in {@link baseUrl}. It resolves independently of `GATEWAY_TARGET`, so this
 * function returns the same host whichever gateway the surrounding run measures.
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
 * host/target resolution as {@link targetUrl} but on the `wss://` scheme the WebSocket upgrade
 * requires.
 *
 * @param {string} path the WebSocket route path, with a leading slash (e.g. `/ws/echo`)
 * @returns {string} the absolute `wss://` URL to open the socket against
 */
export function wsUrl(path) {
    return `${baseUrl().replace(/^https:/, 'wss:').replace(/^http:/, 'ws:')}${path}`;
}

/**
 * Resolves the `host:port` address a k6 gRPC client dials the targeted gateway on — the base URL
 * with its scheme stripped. TLS is negotiated by the client (`plaintext: false`); the gateway
 * forces HTTP/2 to the upstream, so the client speaks ordinary gRPC over TLS to the edge.
 *
 * @returns {string} the `host:port` address (e.g. `api-sheriff:8443`)
 */
export function grpcAddress() {
    return baseUrl().replace(/^https?:\/\//, '');
}
