package de.noizycologne.backend.actor;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "actors")
public class Actor {

    @Id
    @Column(nullable = false, length = 100)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 30)
    private String instagram;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "actor_roles", joinColumns = @JoinColumn(name = "actor_id"))
    @Column(name = "role_code", nullable = false, length = 20)
    private Set<String> roles = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "actor_collective_memberships",
            joinColumns = @JoinColumn(name = "person_id")
    )
    @Column(name = "collective_id", nullable = false, length = 100)
    private Set<String> collectiveIds = new LinkedHashSet<>();

    protected Actor() {
    }

    public Actor(String name, String kind, String instagram,
                 Collection<String> roles, Collection<String> collectiveIds) {
        this.name = name;
        this.kind = kind;
        this.instagram = instagram;
        this.roles = new LinkedHashSet<>(roles);
        this.collectiveIds = new LinkedHashSet<>(collectiveIds);
    }

    @PrePersist
    void assignId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public String getInstagram() {
        return instagram;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public Set<String> getCollectiveIds() {
        return collectiveIds;
    }

    public void update(String name, String kind, String instagram,
                       Collection<String> roles, Collection<String> collectiveIds) {
        this.name = name;
        this.kind = kind;
        this.instagram = instagram;
        this.roles = new LinkedHashSet<>(roles);
        this.collectiveIds = new LinkedHashSet<>(collectiveIds);
    }
}
