package com.example.keycloak.scim;

import org.jboss.logging.Logger;
import org.keycloak.models.RealmModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one ScimClientHolder/ScimSyncService per realm, so different realms can point at different
 * SCIM servers/credentials (set via the "SCIM Provisioning" Realm Settings tab). Rebuilt lazily
 * whenever the effective config for a realm changes; explicitly invalidated by ScimConfigTab right
 * after a save so the next event picks up the new settings instead of a stale cached client.
 */
public final class ScimClientCache {

    private static final Logger LOG = Logger.getLogger(ScimClientCache.class);
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private ScimClientCache() { }

    public static ScimSyncService getOrCreate(RealmModel realm, ScimProvisioningConfig serverDefault) {
        ScimProvisioningConfig effective = serverDefault.overrideFromRealm(realm);
        effective.validate();

        Entry existing = CACHE.get(realm.getId());
        if (existing != null && existing.config.equals(effective)) {
            return existing.syncService;
        }
        if (existing != null) {
            LOG.infof("SCIM config changed for realm %s, rebuilding client", realm.getName());
            existing.holder.close();
        }
        ScimClientHolder holder = new ScimClientHolder(effective);
        ScimSyncService syncService = new ScimSyncService(holder.getRequestBuilder());
        CACHE.put(realm.getId(), new Entry(effective, holder, syncService));
        return syncService;
    }

    /** Called by ScimConfigTab right after a realm's SCIM settings are saved. */
    public static void invalidate(String realmId) {
        Entry e = CACHE.remove(realmId);
        if (e != null) {
            e.holder.close();
        }
    }

    public static void closeAll() {
        CACHE.values().forEach(e -> e.holder.close());
        CACHE.clear();
    }

    private record Entry(ScimProvisioningConfig config, ScimClientHolder holder, ScimSyncService syncService) { }
}
