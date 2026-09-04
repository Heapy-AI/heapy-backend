package com.heapy.home.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@IdClass(UserHomeModuleId.class)
@Table(name = "user_home_modules", schema = "public")
public class UserHomeModule {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "module_code", nullable = false)
    private String moduleCode;

    @Column(name = "is_visible", nullable = false)
    private boolean visible;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserHomeModule() {
    }

    public UserHomeModule(UUID userId, String moduleCode, int displayOrder, Instant now) {
        this.userId = userId;
        this.moduleCode = moduleCode;
        this.visible = true;
        this.displayOrder = (short) displayOrder;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getModuleCode() {
        return moduleCode;
    }

    public boolean isVisible() {
        return visible;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
