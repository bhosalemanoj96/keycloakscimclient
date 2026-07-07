package com.example.keycloak.scim;

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
        return handle(response, "create user " + scimUser.getUserName().orElse("?"))
                ? response.getResource().getId().orElse(null)
                : null;
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

    public boolean updateGroup(String scimGroupId, Group scimGroup) {
        ServerResponse<Group> response = withRetry(() ->
                scimRequestBuilder.update(Group.class, EndpointPaths.GROUPS, scimGroupId)
                        .setResource(scimGroup)
                        .sendRequest());
        return handle(response, "update group " + scimGroupId);
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
