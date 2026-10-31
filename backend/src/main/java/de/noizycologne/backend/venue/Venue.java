package de.noizycologne.backend.venue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "venues")
public class Venue {

    @Id
    @Column(nullable = false, length = 100)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 30)
    private String instagram;

    protected Venue() {
    }

    public Venue(String name, String instagram) {
        this.name = name;
        this.instagram = instagram;
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

    public void update(String name, String instagram) {
        this.name = name;
        this.instagram = instagram;
    }

    public String getInstagram() {
        return instagram;
    }
}
