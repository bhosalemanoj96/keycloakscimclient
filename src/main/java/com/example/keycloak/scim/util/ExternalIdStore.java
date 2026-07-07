package com.example.keycloak.scim.util;

import org.keycloak.models.GroupModel;
import org.keycloak.models.UserModel;

/**
 * Reads/writes the SCIM server's resource ID on the originating Keycloak user/group as a single
 * attribute, so subsequent calls know whether to POST (create) or PUT/PATCH (update).
 *
 * All writes happen inside a freshly-opened KeycloakSession within the async job
 * (see ScimEventListenerProvider), so this class only needs to operate on a live model instance —
 * it never opens sessions itself.
 *
 * This attribute is also the reason DELETE cannot be handled from AdminEvent alone: once the
 * entity is gone, this attribute is gone with it. DELETE is instead handled via pre-removal model
 * events (see ScimEventListenerProviderFactory) where the attribute is still readable at the
 * moment of deletion.
 */
public final class ExternalIdStore {

    private ExternalIdStore() { }

    public static String getUserExternalId(UserModel user, String attributeName) {
        return user.getFirstAttribute(attributeName);
    }

    public static void setUserExternalId(UserModel user, String attributeName, String scimId) {
        user.setSingleAttribute(attributeName, scimId);
    }

    public static String getGroupExternalId(GroupModel group, String attributeName) {
        return group.getFirstAttribute(attributeName);
    }

    public static void setGroupExternalId(GroupModel group, String attributeName, String scimId) {
        group.setSingleAttribute(attributeName, scimId);
    }
}
