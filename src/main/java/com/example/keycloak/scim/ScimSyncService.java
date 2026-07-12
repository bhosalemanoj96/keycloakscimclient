package com.example.keycloak.scim;

import com.fasterxml.jackson.databind.node.TextNode;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import de.captaingoldfish.scim.sdk.client.builder.BulkBuilder;
import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.constants.EndpointPaths;
import de.captaingoldfish.scim.sdk.common.constants.enums.HttpMethod;
import de.captaingoldfish.scim.sdk.common.constants.enums.PatchOp;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.ResourceNode;
import de.captaingoldfish.scim.sdk.common.resources.User;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Member;
import de.captaingoldfish.scim.sdk.common.response.BulkResponse;
import de.captaingoldfish.scim.sdk.common.response.BulkResponseOperation;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

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

    // ---------- Bulk (RFC 7644 §3.7) ----------
    //
    // Genuinely uses the SCIM /Bulk endpoint — a single HTTP request carrying many operations —
    // rather than one HTTP call per resource, unlike everything else in this class. Used by
    // ScimBulkSyncService for the initial bootstrap/bulk-load sync.
    //
    // Confirmed against SDK javadoc: BulkResponse/BulkResponseOperation really do live in
    // de.captaingoldfish.scim.sdk.common.response. BulkResponseOperation has no dedicated getId() —
    // confirmed via its constructor signature (HttpMethod, String, ETag, String, Integer,
    // ErrorResponse), no id parameter — so the new resource's id is extracted from the trailing
    // path segment of getLocation() instead.
    //
    // Still unverified: the wiki's own bulk example is hand-written for exactly two fixed
    // operations chained fluently; the loop below reassigning `chain = chain.next()...` for a
    // dynamic-sized list is my adaptation of that pattern, not something confirmed to compile —
    // it depends on .next() returning something that still exposes .bulkRequestOperation(...) with
    // the same fluent shape. Also unverified: HttpMethod's exact package (guessed as
    // de.captaingoldfish.scim.sdk.common.constants.enums, matching PatchOp's confirmed location).
    // If either doesn't compile, paste the error.

    /**
     * Bulk-creates many Users in one SCIM /Bulk request.
     * @param usersByBulkId map of caller-chosen correlation key (this extension uses the Keycloak
     *                      user's own ID) to the User resource to create.
     * @return map of that same correlation key to the SCIM server's assigned id, for every
     *         operation that succeeded. Keys for failed operations are simply absent.
     */
    public Map<String, String> bulkCreateUsers(Map<String, User> usersByBulkId) {
        return bulkCreate(EndpointPaths.USERS, usersByBulkId, "user");
    }

    /** Same as {@link #bulkCreateUsers} but for Groups. */
    public Map<String, String> bulkCreateGroups(Map<String, Group> groupsByBulkId) {
        return bulkCreate(EndpointPaths.GROUPS, groupsByBulkId, "group");
    }

    private <T extends ResourceNode> Map<String, String> bulkCreate(String endpointPath,
                                                                      Map<String, T> resourcesByBulkId,
                                                                      String actionLabel) {
        Map<String, String> results = new LinkedHashMap<>();
        if (resourcesByBulkId.isEmpty()) {
            return results;
        }

        try {
            BulkBuilder bulkBuilder = scimRequestBuilder.bulk();

            // Using var throughout (instead of naming the intermediate builder type explicitly) so
            // this doesn't depend on guessing that type's name correctly — only on .next(),
            // .bulkRequestOperation(...), .data(...), .method(...), .bulkId(...), and .sendRequest()
            // actually existing with these names and a fluent shape consistent across iterations.
            java.util.Iterator<Map.Entry<String, T>> it = resourcesByBulkId.entrySet().iterator();
            Map.Entry<String, T> firstEntry = it.next();
            var chain = bulkBuilder.bulkRequestOperation(endpointPath)
                    .data(firstEntry.getValue())
                    .method(HttpMethod.POST)
                    .bulkId(firstEntry.getKey());

            while (it.hasNext()) {
                Map.Entry<String, T> entry = it.next();
                chain = chain.next()
                        .bulkRequestOperation(endpointPath)
                        .data(entry.getValue())
                        .method(HttpMethod.POST)
                        .bulkId(entry.getKey());
            }

            ServerResponse<BulkResponse> response = chain.sendRequest();

            if (!response.isSuccess() && response.getResource() == null) {
                LOG.errorf("Bulk %s create failed (non-SCIM error response): status=%s body=%s",
                        actionLabel, response.getHttpStatus(), response.getResponseBody());
                return results;
            }

            BulkResponse bulkResponse = response.getResource();
            if (bulkResponse == null) {
                LOG.errorf("Bulk %s create: no BulkResponse resource on the response despite success=%s",
                        actionLabel, response.isSuccess());
                return results;
            }

            for (BulkResponseOperation op : bulkResponse.getBulkResponseOperations()) {
                String bulkId = op.getBulkId().orElse(null);
                if (bulkId == null) continue;
                if (op.getStatus() >= 200 && op.getStatus() < 300) {
                    // No dedicated getId() on BulkResponseOperation (confirmed via its constructor
                    // signature) — extract the new resource's id from the trailing path segment of
                    // getLocation() instead, e.g. ".../Users/abc-123" -> "abc-123".
                    op.getLocation().ifPresent(location -> {
                        String id = location.substring(location.lastIndexOf('/') + 1);
                        if (!id.isBlank()) {
                            results.put(bulkId, id);
                        }
                    });
                } else {
                    LOG.warnf("Bulk %s create: operation with bulkId=%s failed, status=%s",
                            actionLabel, bulkId, op.getStatus());
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Bulk %s create threw an exception building/sending the request", actionLabel);
        }
        return results;
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
    //
    // SCIM (RFC 7643) allows a Group's "members" array to contain entries of type "Group", not just
    // "User" — that's the standard way nested/child groups are represented. addMember/removeMember
    // are generalized to accept the member type so the same mechanism covers both user membership
    // and nested-group membership.

    public boolean addMember(String scimGroupId, String scimUserId) {
        return addMember(scimGroupId, scimUserId, "User");
    }

    public boolean addMember(String scimGroupId, String scimMemberId, String memberType) {
        Member member = Member.builder().value(scimMemberId).type(memberType).build();
        ServerResponse<Group> response = withRetry(() ->
                scimRequestBuilder.patch(Group.class, EndpointPaths.GROUPS, scimGroupId)
                        .addOperation()
                        .path("members")
                        .op(PatchOp.ADD)
                        .valueNode(member)
                        .build()
                        .sendRequest());
        return handle(response, "add " + memberType + " member " + scimMemberId + " to group " + scimGroupId);
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

    /** Same as {@link #addMember(String, String)} but for a child group being nested under a parent. */
    public boolean addGroupChildMember(String parentScimGroupId, String childScimGroupId) {
        return addMember(parentScimGroupId, childScimGroupId, "Group");
    }

    /** Same as {@link #removeMember(String, String)} but for un-nesting a child group from a parent. */
    public boolean removeGroupChildMember(String parentScimGroupId, String childScimGroupId) {
        return removeMember(parentScimGroupId, childScimGroupId);
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
        try {
            if (response.getErrorResponse() == null) {
                LOG.errorf("SCIM %s failed (non-SCIM error response): %s", action, response.getResponseBody());
            } else {
                LOG.errorf("SCIM %s failed: status=%s detail=%s", action,
                        response.getHttpStatus(), response.getErrorResponse().getDetail().orElse("n/a"));
            }
        } catch (Exception e) {
            // The SDK's own SCIM-error-response parser can itself throw if the server's error body
            // doesn't conform to the shape it expects (e.g. a field it expects to be scalar turns
            // out to be a JSON array) — fall back to the raw status/body rather than losing the
            // real failure reason behind an unrelated parser exception.
            LOG.errorf("SCIM %s failed: status=%s (error body could not be parsed as standard SCIM error: %s) rawBody=%s",
                    action, response.getHttpStatus(), e.getMessage(), response.getResponseBody());
        }
        return false;
    }
}
