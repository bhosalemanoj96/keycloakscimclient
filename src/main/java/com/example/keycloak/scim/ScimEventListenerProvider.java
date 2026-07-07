package com.example.keycloak.scim;

import com.example.keycloak.scim.mapper.ScimGroupMapper;
import com.example.keycloak.scim.mapper.ScimUserMapper;
import com.example.keycloak.scim.util.ExternalIdStore;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.User;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reacts to Keycloak events and pushes the corresponding change to the SCIM server configured for
 * that realm (server-level default, optionally overridden per realm via ScimConfigTab).
 *
 * CREATE/UPDATE for Users, Groups and Group membership are driven by AdminEvents (fired for every
 * admin REST / admin console action) here. DELETE is intentionally NOT handled here — see
 * ScimEventListenerProviderFactory, which registers pre-removal model event listeners instead,
 * because by the time an AdminEvent for a DELETE arrives the entity (and its scimExternalId
 * attribute) no longer exists to read.
 *
 * Every sync is dispatched onto a background executor and reopens its own short-lived
 * KeycloakSession via KeycloakModelUtils.runJobInTransaction — the session tied to this provider
 * instance is only valid for the lifetime of the originating request and must never be touched
 * from another thread.
 */
public class ScimEventListenerProvider implements EventListenerProvider {

    private static final Logger LOG = Logger.getLogger(ScimEventListenerProvider.class);

    private static final Pattern USERS_ID = Pattern.compile("^users/([^/]+)$");
    private static final Pattern GROUPS_ID = Pattern.compile("^groups/([^/]+)$");
    private static final Pattern USER_GROUP_MEMBERSHIP = Pattern.compile("^users/([^/]+)/groups/([^/]+)$");

    private final KeycloakSession session;
    private final KeycloakSessionFactory sessionFactory;
    private final ScimProvisioningConfig serverDefaultConfig;
    private final ExecutorService executor;

    public ScimEventListenerProvider(KeycloakSession session, ScimProvisioningConfig serverDefaultConfig,
                                      ExecutorService executor) {
        this.session = session;
        this.sessionFactory = session.getKeycloakSessionFactory();
        this.serverDefaultConfig = serverDefaultConfig;
        this.executor = executor;
    }

    // ---------- User-facing (self-service) events ----------

    @Override
    public void onEvent(Event event) {
        if (event.getRealmId() == null || event.getUserId() == null) {
            return;
        }
        switch (event.getType()) {
            case REGISTER:
            case UPDATE_PROFILE:
                dispatchUserSync(event.getRealmId(), event.getUserId());
                break;
            default:
                // login, logout, refresh token, etc. — not relevant for provisioning
                break;
        }
    }

    // ---------- Admin console / Admin REST API events ----------

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        if (adminEvent.getOperationType() == OperationType.DELETE) {
            return; // handled by pre-removal model listeners, see the factory
        }

        String realmId = adminEvent.getRealmId();
        String resourcePath = adminEvent.getResourcePath();
        ResourceType resourceType = adminEvent.getResourceType();
        if (realmId == null || resourcePath == null || resourceType == null) {
            return;
        }

        if (resourceType == ResourceType.USER) {
            Matcher m = USERS_ID.matcher(resourcePath);
            if (m.matches()) {
                dispatchUserSync(realmId, m.group(1));
            }
        } else if (resourceType == ResourceType.GROUP) {
            Matcher m = GROUPS_ID.matcher(resourcePath);
            if (m.matches()) {
                dispatchGroupSync(realmId, m.group(1));
            }
        } else if (resourceType == ResourceType.GROUP_MEMBERSHIP) {
            Matcher m = USER_GROUP_MEMBERSHIP.matcher(resourcePath);
            if (m.matches()) {
                String userId = m.group(1);
                String groupId = m.group(2);
                boolean joined = adminEvent.getOperationType() == OperationType.CREATE;
                dispatchMembershipSync(realmId, userId, groupId, joined);
            }
        }
    }

    // ---------- Dispatch helpers ----------
    //
    // IMPORTANT: AdminEvent fires *before* the surrounding request's transaction commits. If we
    // submit to the background executor immediately, that job opens its own fresh
    // session/transaction (via runJobInTransaction) which races the commit and may not see the
    // new/updated row yet — getUserById/getGroupById would spuriously return null even though the
    // entity was in fact created. To avoid that race, we defer the executor.submit() itself until
    // this session's transaction actually commits, using enlistAfterCompletion. That callback runs
    // synchronously right after commit, still on the request thread — cheap (it's just a queue
    // submission), so it doesn't meaningfully add latency to the admin REST call. Only the actual
    // outbound SCIM HTTP call happens later, on the background executor.

    private void afterCommit(Runnable job) {
        session.getTransactionManager().enlistAfterCompletion(new org.keycloak.models.KeycloakTransaction() {
            @Override public void begin() { }

            @Override
            public void commit() {
                job.run();
            }

            @Override public void rollback() { }
            @Override public void setRollbackOnly() { }
            @Override public boolean getRollbackOnly() { return false; }
            @Override public boolean isActive() { return false; }
        });
    }

    private void dispatchUserSync(String realmId, String userId) {
        afterCommit(() -> executor.submit(() -> KeycloakModelUtils.runJobInTransaction(sessionFactory, s -> {
            try {
                syncUser(s, realmId, userId);
            } catch (Throwable t) {
                // Throwable, not Exception: a version mismatch between scim-sdk-client and
                // scim-sdk-common (or any other classpath issue) surfaces as NoSuchMethodError /
                // NoClassDefFoundError, which extends Error and would otherwise be silently
                // swallowed by the executor's discarded Future, with nothing logged anywhere.
                LOG.errorf(t, "Failed syncing user %s to SCIM server", userId);
            }
        })));
    }

    private void dispatchGroupSync(String realmId, String groupId) {
        afterCommit(() -> executor.submit(() -> KeycloakModelUtils.runJobInTransaction(sessionFactory, s -> {
            try {
                syncGroup(s, realmId, groupId);
            } catch (Throwable t) {
                LOG.errorf(t, "Failed syncing group %s to SCIM server", groupId);
            }
        })));
    }

    private void dispatchMembershipSync(String realmId, String userId, String groupId, boolean joined) {
        afterCommit(() -> executor.submit(() -> KeycloakModelUtils.runJobInTransaction(sessionFactory, s -> {
            try {
                syncMembership(s, realmId, userId, groupId, joined);
            } catch (Throwable t) {
                LOG.errorf(t, "Failed syncing membership user=%s group=%s to SCIM server", userId, groupId);
            }
        })));
    }

    // ---------- Actual sync logic (runs inside the background job's own session) ----------

    private String syncUser(KeycloakSession s, String realmId, String userId) {
        RealmModel realm = s.realms().getRealm(realmId);
        if (realm == null) return null;
        s.getContext().setRealm(realm); // some internal providers (e.g. Organizations) require this bound, not just passed as an argument
        UserModel user = s.users().getUserById(realm, userId);
        if (user == null) return null; // deleted again before the job ran; nothing to do

        ScimSyncService syncService = ScimClientCache.getOrCreate(realm, serverDefaultConfig);
        String attr = serverDefaultConfig.overrideFromRealm(realm).externalIdAttribute();
        String existingScimId = ExternalIdStore.getUserExternalId(user, attr);
        User scimUser = ScimUserMapper.toScimUser(user);

        if (existingScimId == null) {
            String newId = syncService.createUser(scimUser);
            if (newId != null) {
                ExternalIdStore.setUserExternalId(user, attr, newId);
            }
            return newId;
        } else {
            syncService.updateUser(existingScimId, scimUser);
            return existingScimId;
        }
    }

    private String syncGroup(KeycloakSession s, String realmId, String groupId) {
        RealmModel realm = s.realms().getRealm(realmId);
        if (realm == null) return null;
        s.getContext().setRealm(realm);
        GroupModel group = s.groups().getGroupById(realm, groupId);
        if (group == null) return null;

        ScimSyncService syncService = ScimClientCache.getOrCreate(realm, serverDefaultConfig);
        String attr = serverDefaultConfig.overrideFromRealm(realm).externalIdAttribute();
        String existingScimId = ExternalIdStore.getGroupExternalId(group, attr);
        Group scimGroup = ScimGroupMapper.toScimGroup(group);

        if (existingScimId == null) {
            String newId = syncService.createGroup(scimGroup);
            if (newId != null) {
                ExternalIdStore.setGroupExternalId(group, attr, newId);
            }
            return newId;
        } else {
            syncService.updateGroup(existingScimId, scimGroup);
            return existingScimId;
        }
    }

    private void syncMembership(KeycloakSession s, String realmId, String userId, String groupId, boolean joined) {
        RealmModel realm = s.realms().getRealm(realmId);
        if (realm == null) return;

        // Ensure both sides exist on the SCIM server first (covers the case where membership is
        // set before either the user or group has ever been synced individually).
        String scimUserId = syncUser(s, realmId, userId);
        String scimGroupId = syncGroup(s, realmId, groupId);
        if (scimUserId == null || scimGroupId == null) {
            LOG.warnf("Skipping membership sync, missing SCIM id (user=%s, group=%s)", scimUserId, scimGroupId);
            return;
        }
        ScimSyncService syncService = ScimClientCache.getOrCreate(realm, serverDefaultConfig);
        if (joined) {
            syncService.addMember(scimGroupId, scimUserId);
        } else {
            syncService.removeMember(scimGroupId, scimUserId);
        }
    }

    @Override
    public void close() {
        // per-request KeycloakSession cleanup is handled by Keycloak itself; nothing to release here.
    }
}
