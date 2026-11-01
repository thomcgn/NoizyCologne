package de.noizycologne.backend.genre;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "genres")
public class Genre {
    @Id
    @Column(nullable = false, length = 100)
    private String id;

    @Column(name = "canonical_name", nullable = false, length = 100)
    private String canonicalName;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false)
    private boolean active;

    protected Genre() { }

    public Genre(String canonicalName, String displayName, boolean active) {
        this.canonicalName = canonicalName;
        this.displayName = displayName;
        this.active = active;
    }

    @PrePersist
    void assignId() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public String getCanonicalName() { return canonicalName; }
    public String getDisplayName() { return displayName; }
    public boolean isActive() { return active; }

    public void update(String canonicalName, String displayName, boolean active) {
        this.canonicalName = canonicalName;
        this.displayName = displayName;
        this.active = active;
    }
}
