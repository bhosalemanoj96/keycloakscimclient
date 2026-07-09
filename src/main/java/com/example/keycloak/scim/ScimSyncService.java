package com.example.keycloak.scim;

import com.fasterxml.jackson.databind.node.TextNode;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.constants.EndpointPaths;
import de.captaingoldfish.scim.sdk.common.constants.enums.PatchOp;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.ResourceNode;
import de.captaingoldfish.scim.sdk.common.resources.User;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Member;
import org.jboss.logging.Logger;

/**
 * Thin, retrying wrapper around ScimRequestBuilder covering every operation this extension needs:
 * Create/Update/Delete for both Users and Groups, plus PATCH-based group membership changes.
 *
 * All methods are synchronous and blocking (they perform the HTTP call). The caller
 * (ScimEventListenerProvider) is responsible for running these off the Keycloak request thread.
 */
public class ScimSyncService {

    private static final Logger LOG = Logger.getLogger(ScimSyncService.class);
    private static final int MAX_RETRIES = 3;

    private final ScimRequestBuilder scimRequestBuilder;

    public ScimSyncService(ScimRequestBuilder scimRequestBuilder) {
        this.scimRequestBuilder = scimRequestBuilder;
    }

    // ---------- Users ----------

    /** @return the SCIM server's assigned id for the created user, or null on failure. */
    public String createUser(User scimUser) {
        ServerResponse<User> response = withRetry(() ->
                scimRequestBuilder.create(User.class, EndpointPaths.USERS)
                        .setResource(scimUser)
                        .sendRequest());

        if (handle(response, "create user " + scimUser.getUserName().orElse("?"))) {
            return response.getResource().getId().orElse(null);
        }

        // Reconcile a 409 instead of just failing. Root cause we actually hit in practice: our own
        // retry can resend a POST after a timeout where the *first* attempt's request actually
        // reached the server and succeeded but the response was lost — the retry then correctly
        // gets a 409 for a resource that really does exist, createUser() returns null, and the
        // Keycloak user never gets its scimExternalId recorded even though the SCIM server has a
        // real record. Left unhandled, every future sync for that user 409s forever. Look the
        // existing resource up by userName and adopt its id instead of leaving it orphaned.
        if (response.getHttpStatus() == 409) {
            String userName = scimUser.getUserName().orElse(null);
            if (userName != null) {
                String existingId = findUserIdByUserName(userName);
                if (existingId != null) {
                    LOG.infof("Reconciled 409 conflict for user %s with existing SCIM id %s", userName, existingId);
                    return existingId;
                }
                LOG.errorf("409 conflict for user %s but lookup-by-userName found no match; leaving unresolved", userName);
            }
        }
        return null;
    }

    /**
     * NOTE: exact list/search API shape (method/type names) is unverified against your installed
     * scim-sdk-client 1.32.0 — I don't have confirmed source for the list/query builder the way I
     * do for create/update/delete/patch. If this doesn't compile, the fix is almost certainly just
     * renaming a method/type here (same pattern as the BasicAuth package / httpClientBuilder fixes
     * earlier) — paste the compiler error and I'll correct it.
     */
    private String findUserIdByUserName(String userName) {
        try {
            var response = scimRequestBuilder.list(User.class, EndpointPaths.USERS)
                    .filter("userName eq \"" + userName + "\"")
                    .get()
                    .sendRequest();
            if (response.isSuccess() && response.getResource() != null
                    && !response.getResource().getListedResources().isEmpty()) {
                return response.getResource().getListedResources().get(0).getId().orElse(null);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to look up existing SCIM user by userName=%s: %s", userName, e.getMessage());
        }
        return null;
    }

    public boolean updateUser(String scimUserId, User scimUser) {
        ServerResponse<User> response = withRetry(() ->
                scimRequestBuilder.update(User.class, EndpointPaths.USERS, scimUserId)
                        .setResource(scimUser)
                        .sendRequest());
        return handle(response, "update user " + scimUserId);
    }

    public boolean deleteUser(String scimUserId) {
        ServerResponse<User> response = withRetry(() ->
                scimRequestBuilder.delete(User.class, EndpointPaths.USERS, scimUserId).sendRequest());
        return handle(response, "delete user " + scimUserId);
    }

    // ---------- Groups ----------

    public String createGroup(Group scimGroup) {
        ServerResponse<Group> response = withRetry(() ->
                scimRequestBuilder.create(Group.class, EndpointPaths.GROUPS)
                        .setResource(scimGroup)
                        .sendRequest());
        return handle(response, "create group " + scimGroup.getDisplayName().orElse("?"))
                ? response.getResource().getId().orElse(null)
                : null;
    }

    /**
     * Full-resource replace (PUT). CAUTION: SCIM PUT replaces the entire resource — any field this
     * extension doesn't populate on the Group object (notably "members", since ScimGroupMapper only
     * sets displayName/externalId) will be sent as empty/absent, which a compliant SCIM server will
     * interpret as "clear it". Do NOT use this for a simple rename; use {@link #renameGroup} instead,
     * which only touches displayName via PATCH and leaves membership (managed separately via
     * addMember/removeMember) untouched. Kept here for cases where you really do want a full
     * resource replace with a fully-populated Group object.
     */
    public boolean updateGroup(String scimGroupId, Group scimGroup) {
        ServerResponse<Group> response = withRetry(() ->
                scimRequestBuilder.update(Group.class, EndpointPaths.GROUPS, scimGroupId)
                        .setResource(scimGroup)
                        .sendRequest());
        return handle(response, "update group " + scimGroupId);
    }

    /** PATCH-only displayName update — does not touch membership. This is what group renames use. */
    public boolean renameGroup(String scimGroupId, String newDisplayName) {
        ServerResponse<Group> response = withRetry(() ->
                scimRequestBuilder.patch(Group.class, EndpointPaths.GROUPS, scimGroupId)
                        .addOperation()
                        .path("displayName")
                        .op(PatchOp.REPLACE)
                        .valueNode(TextNode.valueOf(newDisplayName))
                        .build()
                        .sendRequest());
        return handle(response, "rename group " + scimGroupId);
    }

    public boolean deleteGroup(String scimGroupId) {
        ServerResponse<Group> response = withRetry(() ->
                scimRequestBuilder.delete(Group.class, EndpointPaths.GROUPS, scimGroupId).sendRequest());
        return handle(response, "delete group " + scimGroupId);
    }

    // ---------- Group membership (PATCH) ----------

    public boolean addMember(String scimGroupId, String scimUserId) {
        Member member = Member.builder().value(scimUserId).type("User").build();
        ServerResponse<Group> response = withRetry(() ->
                scimRequestBuilder.patch(Group.class, EndpointPaths.GROUPS, scimGroupId)
                        .addOperation()
                        .path("members")
                        .op(PatchOp.ADD)
                        .valueNode(member)
                        .build()
                        .sendRequest());
        return handle(response, "add member " + scimUserId + " to group " + scimGroupId);
    }

    public boolean removeMember(String scimGroupId, String scimUserId) {
        ServerResponse<Group> response = withRetry(() ->
                scimRequestBuilder.patch(Group.class, EndpointPaths.GROUPS, scimGroupId)
                        .addOperation()
                        .path("members[value eq \"" + scimUserId + "\"]")
                        .op(PatchOp.REMOVE)
                        .build()
                        .sendRequest());
        return handle(response, "remove member " + scimUserId + " from group " + scimGroupId);
    }

    // ---------- Plumbing ----------

    private <T extends ResourceNode> ServerResponse<T> withRetry(java.util.function.Supplier<ServerResponse<T>> call) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                lastError = e;
                LOG.warnf("SCIM request attempt %d/%d failed: %s", attempt, MAX_RETRIES, e.getMessage());
                sleepBackoff(attempt);
            }
        }
        throw lastError;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(300L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean handle(ServerResponse<?> response, String action) {
        if (response.isSuccess()) {
            LOG.debugf("SCIM %s succeeded", action);
            return true;
        }
        if (response.getErrorResponse() == null) {
            LOG.errorf("SCIM %s failed (non-SCIM error response): %s", action, response.getResponseBody());
        } else {
            LOG.errorf("SCIM %s failed: status=%s detail=%s", action,
                    response.getHttpStatus(), response.getErrorResponse().getDetail().orElse("n/a"));
        }
        return false;
    }
}
