package com.example.keycloak.scim.mapper;

import de.captaingoldfish.scim.sdk.common.resources.User;
import de.captaingoldfish.scim.sdk.common.resources.complex.Name;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Email;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.Collections;
import java.util.List;

/**
 * Translates Keycloak user data into SCIM User resources for outbound provisioning.
 *
 * Kept deliberately simple (core User schema only) for phase 1. If you need Enterprise User
 * extension attributes (employeeNumber, department, manager, etc.) map them here using
 * User.builder()... with the urn:ietf:params:scim:schemas:extension:enterprise:2.0:User schema,
 * same as you're already doing server-side for the SCIM-SDK-Server UserResourceHandler.
 */
public final class ScimUserMapper {

    private ScimUserMapper() { }

    public static User toScimUser(UserRepresentation kcUser) {
        List<Email> emails = kcUser.getEmail() == null
                ? Collections.emptyList()
                : List.of(Email.builder().value(kcUser.getEmail()).primary(true).build());

        return User.builder()
                .externalId(kcUser.getId())               // Keycloak's own ID, useful for the SCIM server to correlate
                .userName(kcUser.getUsername())
                .name(Name.builder()
                        .givenName(kcUser.getFirstName())
                        .familyName(kcUser.getLastName())
                        .build())
                .emails(emails)
                .active(kcUser.isEnabled() == null || kcUser.isEnabled())
                .build();
    }

    /** Same mapping, but from the live UserModel — used by the pre-removal listener. */
    public static User toScimUser(UserModel kcUser) {
        List<Email> emails = kcUser.getEmail() == null
                ? Collections.emptyList()
                : List.of(Email.builder().value(kcUser.getEmail()).primary(true).build());

        return User.builder()
                .externalId(kcUser.getId())
                .userName(kcUser.getUsername())
                .name(Name.builder()
                        .givenName(kcUser.getFirstName())
                        .familyName(kcUser.getLastName())
                        .build())
                .emails(emails)
                .active(kcUser.isEnabled())
                .build();
    }
}
