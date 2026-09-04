package com.heapy.home.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class UserHomeModuleId implements Serializable {

    private UUID userId;
    private String moduleCode;

    public UserHomeModuleId() {
    }

    public UserHomeModuleId(UUID userId, String moduleCode) {
        this.userId = userId;
        this.moduleCode = moduleCode;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof UserHomeModuleId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(moduleCode, that.moduleCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, moduleCode);
    }
}
