package com.example.keycloak.scim.ui;

import com.example.keycloak.scim.ScimClientCache;
import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.services.ui.extend.UiTabProvider;
import org.keycloak.services.ui.extend.UiTabProviderFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds a "SCIM Provisioning" tab to Realm Settings, so the SCIM base URL / auth mode / credentials
 * can be set per realm from the admin console instead of only via server-level config
 * (keycloak.conf / KC_SPI_* env vars, see ScimProvisioningConfig). Anything left blank here falls
 * back to the server-level default.
 *
 * ============================================================================================
 * IMPORTANT — verify before relying on this:
 *
 * 1. This requires the experimental "declarative-ui" feature to be enabled on the server:
 *        kc.sh start --features=declarative-ui
 *    (or --features=preview, depending on your version). The org.keycloak.services.ui.extend.*
 *    SPI is a Keycloak preview feature at the time of writing — confirm it's still present, under
 *    the same feature flag, with the same interface shape, in the exact Keycloak version you run.
 *    This is NOT part of Keycloak's stable public API in the way EventListenerProviderFactory is.
 *
 * 2. This implementation was written against the shape shown in Keycloak's own
 *    extend-admin-console-spi quickstart (ThemeUiTab.java), which only demonstrates onCreate() for
 *    persisting the form and getConfigProperties() for declaring fields. How the admin console
 *    pre-fills the form with previously-saved values was not something I could verify from that
 *    example alone — check the actual saved values round-trip correctly in your Keycloak version
 *    before trusting this for anything beyond a dev/test setup.
 *
 * 3. Values are stored as plain realm attributes (readable via the realm's export/REST
 *    representation like any other attribute) — Keycloak does not encrypt arbitrary realm
 *    attributes at rest. If the bearer token / basic auth password need to be secret at rest,
 *    store a vault alias here instead and resolve the real secret through Keycloak's vault SPI in
 *    ScimClientHolder, rather than the raw value.
 * ============================================================================================
 */
public class ScimConfigTab implements UiTabProvider, UiTabProviderFactory<ComponentModel> {

    private static final String ATTR_PREFIX = "scim.";

    @Override
    public String getId() {
        return "SCIM Provisioning";
    }

    @Override
    public String getHelpText() {
        return "Configure the SCIM server this realm provisions users and groups to.";
    }

    @Override
    public void init(Config.Scope config) { }

    @Override
    public void postInit(KeycloakSessionFactory factory) { }

    @Override
    public void close() { }

    @Override
    public void onCreate(KeycloakSession session, RealmModel realm, ComponentModel model) {
        persist(session, realm.getId(), model);
    }

    /**
     * IMPORTANT: ComponentFactory's default onUpdate() is a no-op. Keycloak auto-creates an empty
     * ComponentModel for this tab the first time it's rendered (triggering onCreate with blank
     * values), so by the time an admin actually fills in the form and hits Save, the component
     * already exists — that save is an UPDATE, not a create. Without this override, every real
     * edit made through the UI was silently discarded, which is exactly why the extension kept
     * falling back to the server-level (keycloak.conf) token instead of the one set here.
     */
    @Override
    public void onUpdate(KeycloakSession session, RealmModel realm, ComponentModel oldModel, ComponentModel newModel) {
        persist(session, realm.getId(), newModel);
    }

    /**
     * Writes realm attributes in their own short-lived transaction via
     * KeycloakModelUtils.runJobInTransaction, rather than mutating the RealmModel instance handed
     * to onCreate/onUpdate directly. That instance's write survived only in the runtime cache, not
     * the database — visible within the same server run (so a re-save "worked"), but gone after a
     * restart once the cache rebuilds from the DB. Opening our own job guarantees a real commit.
     */
    private void persist(KeycloakSession callbackSession, String realmId, ComponentModel model) {
        KeycloakSessionFactory sessionFactory = callbackSession.getKeycloakSessionFactory();
        KeycloakModelUtils.runJobInTransaction(sessionFactory, s -> {
            RealmModel realm = s.realms().getRealm(realmId);
            if (realm == null) return;

            setIfPresent(realm, model, "baseUrl");
            setIfPresent(realm, model, "authMode");
            setIfPresent(realm, model, "basicUsername");
            setIfPresent(realm, model, "basicPassword");
            setIfPresent(realm, model, "bearerToken");
            setIfPresent(realm, model, "externalIdAttribute");
        });

        // Confirm the write actually landed in the DB (read back via a second, fresh job) rather
        // than trusting the in-memory object we just wrote to.
        KeycloakModelUtils.runJobInTransaction(sessionFactory, s -> {
            RealmModel realm = s.realms().getRealm(realmId);
            if (realm != null) {
                System.out.println("SCIM_TAB_DEBUG committed value read back fresh: bearerToken="
                        + realm.getAttribute(ATTR_PREFIX + "bearerToken"));
            }
        });

        // Force the next event for this realm to rebuild its SCIM client with the new settings,
        // rather than keep using a cached client built from the previous config.
        ScimClientCache.invalidate(realmId);
    }

    private void setIfPresent(RealmModel realm, ComponentModel model, String field) {
        String value = model.get(field);
        if (value != null) {
            realm.setAttribute(ATTR_PREFIX + field, value);
        }
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigurationBuilder builder = ProviderConfigurationBuilder.create();

        builder.property()
                .name("baseUrl")
                .label("SCIM base URL")
                .helpText("e.g. https://your-scim-server.example.com/scim/v2. Leave blank to use the server-wide default.")
                .type(ProviderConfigProperty.STRING_TYPE)
                .add();

        builder.property()
                .name("authMode")
                .label("Auth mode")
                .helpText("How this realm authenticates to the SCIM server")
                .type(ProviderConfigProperty.LIST_TYPE)
                .options("NONE", "BASIC", "BEARER")
                .defaultValue("NONE")
                .add();

        builder.property()
                .name("basicUsername")
                .label("Basic auth username")
                .helpText("Only used when auth mode is BASIC")
                .type(ProviderConfigProperty.STRING_TYPE)
                .add();

        builder.property()
                .name("basicPassword")
                .label("Basic auth password")
                .helpText("Only used when auth mode is BASIC")
                .type(ProviderConfigProperty.PASSWORD)
                .add();

        builder.property()
                .name("bearerToken")
                .label("Bearer token")
                .helpText("Only used when auth mode is BEARER")
                .type(ProviderConfigProperty.PASSWORD)
                .add();

        builder.property()
                .name("externalIdAttribute")
                .label("External ID attribute name")
                .helpText("Keycloak user/group attribute used to store the SCIM server's resource ID. Leave blank to use the server default (scimExternalId).")
                .type(ProviderConfigProperty.STRING_TYPE)
                .add();

        return builder.build();
    }

    @Override
    public String getPath() {
        return "/:realm/realm-settings/:tab?";
    }

    @Override
    public Map<String, String> getParams() {
        Map<String, String> params = new HashMap<>();
        params.put("tab", "scim-provisioning");
        return params;
    }
}
