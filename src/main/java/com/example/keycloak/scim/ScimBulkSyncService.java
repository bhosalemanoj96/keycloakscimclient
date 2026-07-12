package com.example.keycloak.scim;

import com.example.keycloak.scim.mapper.ScimGroupMapper;
import com.example.keycloak.scim.mapper.ScimUserMapper;
import com.example.keycloak.scim.util.ExternalIdStore;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.User;
import org.jboss.logging.Logger;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot bulk push of every user and group currently in a realm to its configured SCIM server —
 * useful for initial bootstrap/onboarding, and for exercising the SCIM server's real Bulk endpoint
 * (RFC 7644 §3.7) directly, rather than the incremental, event-driven sync everything else in this
 * extension does.
 *
 * Genuinely uses ScimSyncService.bulkCreateGroups/bulkCreateUsers (single /Bulk HTTP request per
 * stage, via the SDK's real bulk() builder) for anything new, rather than one HTTP call per entity.
 * Order:
 *   1. Every group needing creation, batched into one bulk request (flat — parent/child nesting is
 *      NOT established within this same bulk batch, since a child's parent may also be newly
 *      created in the same batch and its real SCIM id isn't known until the batch response comes
 *      back). Groups that already have a SCIM id are renamed individually instead (lower volume,
 *      not bulk-critical).
 *   2. Every user needing creation, similarly batched. Existing users are updated individually.
 *   3. NOW that every group and user has a real SCIM id, nested-group membership and user-group
 *      membership are linked — individually (PATCH per relationship), same as the event-driven path.
 *
 * This intentionally does NOT reuse ScimEventListenerProvider's private sync methods — those are
 * tightly coupled to the event-dispatch/diffing model (LAST_KNOWN_CHILDREN_ATTR,
 * LAST_KNOWN_PARENT_ATTR, transaction-boundary handling) built up across many incremental fixes.
 *
 * UNVERIFIED: RealmModel.getTopLevelGroupsStream() (also used and flagged elsewhere), and
 * UserProvider.getGroupMembersStream(realm, group). getUsersStream(realm) was confirmed via
 * Keycloak's own javadocs to no longer exist in recent versions — fixed to use
 * searchForUserStream(realm, Map.of()) instead. The bulk create methods this delegates to
 * (ScimSyncService.bulkCreateGroups/bulkCreateUsers) carry additional, higher-than-usual
 * uncertainty of their own — see the comments there.
 */
public class ScimBulkSyncService {

    private static final Logger LOG = Logger.getLogger(ScimBulkSyncService.class);

    public void syncAll(KeycloakSession s, RealmModel realm, ScimProvisioningConfig serverDefaultConfig) {
        ScimSyncService syncService = ScimClientCache.getOrCreate(realm, serverDefaultConfig);
        String attr = serverDefaultConfig.overrideFromRealm(realm).externalIdAttribute();

        LOG.infof("Starting full SCIM bulk sync for realm %s", realm.getName());

        AtomicInteger groupsCreated = new AtomicInteger();
        AtomicInteger groupsUpdated = new AtomicInteger();
        AtomicInteger groupsFailed = new AtomicInteger();
        AtomicInteger usersCreated = new AtomicInteger();
        AtomicInteger usersUpdated = new AtomicInteger();
        AtomicInteger usersFailed = new AtomicInteger();
        AtomicInteger membershipsOk = new AtomicInteger();
        AtomicInteger membershipsFailed = new AtomicInteger();

        // ---------- Stage 1: groups ----------
        List<GroupModel> allGroups = new ArrayList<>();
        realm.getTopLevelGroupsStream().forEach(g -> collectGroupsRecursive(g, allGroups));

        Map<String, Group> newGroupsByBulkId = new LinkedHashMap<>();
        for (GroupModel g : allGroups) {
            if (ExternalIdStore.getGroupExternalId(g, attr) == null) {
                newGroupsByBulkId.put(g.getId(), ScimGroupMapper.toScimGroup(g));
            }
        }
        if (!newGroupsByBulkId.isEmpty()) {
            Map<String, String> created = syncService.bulkCreateGroups(newGroupsByBulkId);
            for (GroupModel g : allGroups) {
                String scimId = created.get(g.getId());
                if (scimId != null) {
                    ExternalIdStore.setGroupExternalId(g, attr, scimId);
                    groupsCreated.incrementAndGet();
                } else if (newGroupsByBulkId.containsKey(g.getId())) {
                    groupsFailed.incrementAndGet();
                }
            }
        }
        // Existing groups: rename individually (not bulk-critical, typically low volume).
        for (GroupModel g : allGroups) {
            String existingScimId = ExternalIdStore.getGroupExternalId(g, attr);
            if (existingScimId != null && !newGroupsByBulkId.containsKey(g.getId())) {
                syncService.renameGroup(existingScimId, g.getName());
                groupsUpdated.incrementAndGet();
            }
        }

        // ---------- Stage 2: users ----------
        List<UserModel> allUsers = new ArrayList<>();
        s.users().searchForUserStream(realm, java.util.Map.of()).forEach(allUsers::add);

        Map<String, User> newUsersByBulkId = new LinkedHashMap<>();
        for (UserModel u : allUsers) {
            if (ExternalIdStore.getUserExternalId(u, attr) == null) {
                newUsersByBulkId.put(u.getId(), ScimUserMapper.toScimUser(u));
            }
        }
        if (!newUsersByBulkId.isEmpty()) {
            Map<String, String> created = syncService.bulkCreateUsers(newUsersByBulkId);
            for (UserModel u : allUsers) {
                String scimId = created.get(u.getId());
                if (scimId != null) {
                    ExternalIdStore.setUserExternalId(u, attr, scimId);
                    usersCreated.incrementAndGet();
                } else if (newUsersByBulkId.containsKey(u.getId())) {
                    usersFailed.incrementAndGet();
                }
            }
        }
        for (UserModel u : allUsers) {
            String existingScimId = ExternalIdStore.getUserExternalId(u, attr);
            if (existingScimId != null && !newUsersByBulkId.containsKey(u.getId())) {
                syncService.updateUser(existingScimId, ScimUserMapper.toScimUser(u));
                usersUpdated.incrementAndGet();
            }
        }

        // ---------- Stage 3: nested-group and user-group membership, now that everything has a SCIM id ----------
        for (GroupModel g : allGroups) {
            String scimId = ExternalIdStore.getGroupExternalId(g, attr);
            String parentId = g.getParentId();
            if (scimId != null && parentId != null && !parentId.isBlank() && !parentId.equals(realm.getId())) {
                GroupModel parent = s.groups().getGroupById(realm, parentId);
                String parentScimId = parent != null ? ExternalIdStore.getGroupExternalId(parent, attr) : null;
                if (parentScimId != null) {
                    syncService.addGroupChildMember(parentScimId, scimId);
                }
            }

            if (scimId != null) {
                s.users().getGroupMembersStream(realm, g).forEach(user -> {
                    String userScimId = ExternalIdStore.getUserExternalId(user, attr);
                    if (userScimId != null) {
                        if (syncService.addMember(scimId, userScimId)) {
                            membershipsOk.incrementAndGet();
                        } else {
                            membershipsFailed.incrementAndGet();
                        }
                    } else {
                        membershipsFailed.incrementAndGet();
                    }
                });
            }
        }

        LOG.infof("Full SCIM bulk sync complete for realm %s: groups created=%d updated=%d failed=%d; "
                        + "users created=%d updated=%d failed=%d; memberships ok=%d failed=%d",
                realm.getName(), groupsCreated.get(), groupsUpdated.get(), groupsFailed.get(),
                usersCreated.get(), usersUpdated.get(), usersFailed.get(),
                membershipsOk.get(), membershipsFailed.get());
    }

    private void collectGroupsRecursive(GroupModel group, List<GroupModel> out) {
        out.add(group);
        group.getSubGroupsStream().forEach(child -> collectGroupsRecursive(child, out));
    }
}
