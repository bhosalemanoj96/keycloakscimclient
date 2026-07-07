package com.example.keycloak.scim.mapper;

import de.captaingoldfish.scim.sdk.common.resources.Group;
import org.keycloak.models.GroupModel;
import org.keycloak.representations.idm.GroupRepresentation;

public final class ScimGroupMapper {

    private ScimGroupMapper() { }

    public static Group toScimGroup(GroupRepresentation kcGroup) {
        return Group.builder()
                .externalId(kcGroup.getId())
                .displayName(kcGroup.getName())
                .build();
    }

    public static Group toScimGroup(GroupModel kcGroup) {
        return Group.builder()
                .externalId(kcGroup.getId())
                .displayName(kcGroup.getName())
                .build();
    }
}
