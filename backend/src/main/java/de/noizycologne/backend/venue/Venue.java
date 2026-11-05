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

    @Column(length = 2048)
    private String websiteUrl = "";
    @Column(length = 2048)
    private String eventsUrl = "";
    private boolean discoveryEnabled;
    private java.time.Instant lastDiscoveryAt;
    @Column(length = 60)
    private String discoveryStatus = "NOT_CHECKED";

    public String getWebsiteUrl() { return websiteUrl; }
    public String getEventsUrl() { return eventsUrl; }
    public boolean isDiscoveryEnabled() { return discoveryEnabled; }
    public java.time.Instant getLastDiscoveryAt() { return lastDiscoveryAt; }
    public String getDiscoveryStatus() { return discoveryStatus; }
    public void configureDiscovery(String website, String events, boolean enabled) {
        de.noizycologne.backend.discovery.SafeUrls.validateOptional(website);
        de.noizycologne.backend.discovery.SafeUrls.validateOptional(events);
        if (enabled && website.isBlank() && events.isBlank())
            throw new IllegalArgumentException("Eine Website- oder Event-URL ist erforderlich");
        websiteUrl = website; eventsUrl = events; discoveryEnabled = enabled;
    }

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
