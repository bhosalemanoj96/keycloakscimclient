package com.example.keycloak.scim;

import org.keycloak.Config;
import org.keycloak.models.RealmModel;

/**
 * Server-level default SCIM config (from keycloak.conf / KC_SPI_* env vars), which individual
 * realms can override via the "SCIM Provisioning" Realm Settings tab (see ScimConfigTab) — those
 * overrides are stored as realm attributes and layered on top of this default in
 * {@link #overrideFromRealm(RealmModel)}.
 */
public record ScimProvisioningConfig(
        String baseUrl,
        AuthMode authMode,
        String basicUsername,
        String basicPassword,
        String bearerToken,
        String externalIdAttribute,
        int asyncThreads,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        boolean skipTlsVerify
) {

    public enum AuthMode { NONE, BASIC, BEARER }

    private static final String REALM_ATTR_PREFIX = "scim.";

    public static ScimProvisioningConfig from(Config.Scope scope) {
        String baseUrl = firstNonNull(scope.get("scim-base-url"), System.getenv("SCIM_BASE_URL"));
        AuthMode authMode = AuthMode.valueOf(
                firstNonNull(scope.get("auth-mode"), System.getenv("SCIM_AUTH_MODE"), "BEARER").toUpperCase());
        String basicUsername = firstNonNull(scope.get("basic-username"), System.getenv("SCIM_BASIC_USERNAME"));
        String basicPassword = firstNonNull(scope.get("basic-password"), System.getenv("SCIM_BASIC_PASSWORD"));
        String bearerToken = firstNonNull(scope.get("bearer-token"), System.getenv("SCIM_BEARER_TOKEN"));
        String externalIdAttribute = firstNonNull(scope.get("external-id-attribute"),
                System.getenv("SCIM_EXTERNAL_ID_ATTRIBUTE"), "scimExternalId");
        int asyncThreads = Integer.parseInt(
                firstNonNull(scope.get("async-threads"), System.getenv("SCIM_ASYNC_THREADS"), "2"));
        int connectTimeoutSeconds = Integer.parseInt(
                firstNonNull(scope.get("connect-timeout-seconds"), System.getenv("SCIM_CONNECT_TIMEOUT"), "5"));
        int requestTimeoutSeconds = Integer.parseInt(
                firstNonNull(scope.get("request-timeout-seconds"), System.getenv("SCIM_REQUEST_TIMEOUT"), "10"));
        boolean skipTlsVerify = Boolean.parseBoolean(
                firstNonNull(scope.get("skip-tls-verify"), System.getenv("SCIM_SKIP_TLS_VERIFY"), "false"));

        // baseUrl may legitimately be blank here — it's fine if every realm supplies its own via
        // the admin console tab instead. It's only required by the time overrideFromRealm() is
        // applied for an actual event and still comes up empty; see validate().
        return new ScimProvisioningConfig(baseUrl, authMode, basicUsername, basicPassword, bearerToken,
                externalIdAttribute, asyncThreads, connectTimeoutSeconds, requestTimeoutSeconds, skipTlsVerify);
    }

    /**
     * Layers this realm's overrides (set via the Realm Settings -> SCIM Provisioning tab, stored
     * as realm attributes) on top of the server-level default. Only attributes actually set at the
     * realm level override; anything blank/unset falls back to the server default.
     */
    public ScimProvisioningConfig overrideFromRealm(RealmModel realm) {
        String realmAuthMode = realm.getAttribute(REALM_ATTR_PREFIX + "authMode");
        return new ScimProvisioningConfig(
                firstNonNull(realm.getAttribute(REALM_ATTR_PREFIX + "baseUrl"), baseUrl),
                (realmAuthMode != null && !realmAuthMode.isBlank()) ? AuthMode.valueOf(realmAuthMode.toUpperCase()) : authMode,
                firstNonNull(realm.getAttribute(REALM_ATTR_PREFIX + "basicUsername"), basicUsername),
                firstNonNull(realm.getAttribute(REALM_ATTR_PREFIX + "basicPassword"), basicPassword),
                firstNonNull(realm.getAttribute(REALM_ATTR_PREFIX + "bearerToken"), bearerToken),
                firstNonNull(realm.getAttribute(REALM_ATTR_PREFIX + "externalIdAttribute"), externalIdAttribute),
                asyncThreads, connectTimeoutSeconds, requestTimeoutSeconds, skipTlsVerify);
    }

    public void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "SCIM provisioning is not configured for this realm: set a SCIM base URL either " +
                    "server-wide (spi-events-listener-scim-provisioning-scim-base-url) or per-realm " +
                    "via Realm Settings -> SCIM Provisioning.");
        }
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
