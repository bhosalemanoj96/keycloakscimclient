package com.example.keycloak.scim;

import com.example.keycloak.scim.util.ExternalIdStore;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Registers the "scim-provisioning" event listener.
 *
 * Enable it in the realm's Events Config (Admin Console -> Realm Settings -> Events ->
 * Event Listeners), or via kcadm:
 *   kcadm.sh update events/config -r myrealm -s 'eventsListeners=["jboss-logging","scim-provisioning"]'
 *
 * IMPORTANT: "Save events" / admin event "include representation" must be turned ON for admin
 * events in the realm's event config, otherwise AdminEvent payloads may arrive without the detail
 * this extension needs. (adminEventsDetailsEnabled=true)
 *
 * The SCIM base URL / auth mode / credentials configured here via init() are the *server-level
 * default*. Individual realms can override any of them from Realm Settings -> SCIM Provisioning
 * (see ScimConfigTab) without touching server config at all.
 */
public class ScimEventListenerProviderFactory implements EventListenerProviderFactory {

    private static final Logger LOG = Logger.getLogger(ScimEventListenerProviderFactory.class);
    public static final String PROVIDER_ID = "scim-provisioning";

    private ScimProvisioningConfig serverDefaultConfig;
    private ExecutorService executor;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new ScimEventListenerProvider(session, serverDefaultConfig, executor);
    }

    @Override
    public void init(Config.Scope scope) {
        this.serverDefaultConfig = ScimProvisioningConfig.from(scope);
        this.executor = Executors.newFixedThreadPool(serverDefaultConfig.asyncThreads(),
                runnable -> {
                    Thread t = new Thread(runnable, "scim-provisioning-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * Registers the DELETE-path handling. UserModel.UserRemovedEvent / GroupModel.GroupRemovedEvent
     * fire synchronously, inside the same transaction as the removal, while the entity (and its
     * scimExternalId attribute) is still readable — unlike AdminEvent for DELETE, which fires after
     * the fact with no representation. We read the id here synchronously (cheap, no I/O) and only
     * dispatch the actual outbound SCIM DELETE call asynchronously, since that's the only part that
     * requires a network round trip.
     */
    @Override
    public void postInit(KeycloakSessionFactory factory) {
        factory.register(event -> {
            if (event instanceof UserModel.UserRemovedEvent removedEvent) {
                RealmModel realm = removedEvent.getRealm();
                String attr = serverDefaultConfig.overrideFromRealm(realm).externalIdAttribute();
                String scimId = ExternalIdStore.getUserExternalId(removedEvent.getUser(), attr);
                if (scimId != null) {
                    executor.submit(() -> {
                        try {
                            ScimSyncService syncService = ScimClientCache.getOrCreate(realm, serverDefaultConfig);
                            if (!syncService.deleteUser(scimId)) {
                                LOG.errorf("Failed to delete SCIM user %s after Keycloak user removal", scimId);
                            }
                        } catch (Throwable t) {
                            LOG.errorf(t, "Failed to delete SCIM user %s after Keycloak user removal", scimId);
                        }
                    });
                }
            } else if (event instanceof GroupModel.GroupRemovedEvent removedEvent) {
                RealmModel realm = removedEvent.getRealm();
                String attr = serverDefaultConfig.overrideFromRealm(realm).externalIdAttribute();
                String scimId = ExternalIdStore.getGroupExternalId(removedEvent.getGroup(), attr);
                if (scimId != null) {
                    executor.submit(() -> {
                        try {
                            ScimSyncService syncService = ScimClientCache.getOrCreate(realm, serverDefaultConfig);
                            if (!syncService.deleteGroup(scimId)) {
                                LOG.errorf("Failed to delete SCIM group %s after Keycloak group removal", scimId);
                            }
                        } catch (Throwable t) {
                            LOG.errorf(t, "Failed to delete SCIM group %s after Keycloak group removal", scimId);
                        }
                    });
                }
            }
        });
        LOG.info("SCIM provisioning event listener registered (create/update via AdminEvent, delete via model events)");
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdown();
        }
        ScimClientCache.closeAll();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
