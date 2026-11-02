package de.noizycologne.backend.calendar;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "calendar_entries")
public class CalendarEntry {

    @Id
    @Column(nullable = false, length = 100)
    private String id;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "location_id", nullable = false, length = 100)
    private String locationId;

    @Column(name = "entry_type", nullable = false, length = 20)
    private String entryType;

    @Column(nullable = false)
    private String notes;

    @Column(name = "entry_price", nullable = false, length = 100)
    private String entryPrice;

    @Column(name = "party_id", length = 100)
    private String partyId;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(length = 200)
    private String reason;

    @Column(name = "event_flyer_media_id", length = 100)
    private String eventFlyerMediaId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "calendar_entry_mentions",
            joinColumns = @JoinColumn(name = "entry_id")
    )
    @Column(name = "actor_id", nullable = false, length = 100)
    private Set<String> mentionDjIds = new LinkedHashSet<>();

    protected CalendarEntry() {
    }

    public CalendarEntry(LocalDate eventDate, String locationId, String entryType,
                         String notes, String partyId, LocalTime startTime,
                         String reason, Collection<String> mentionDjIds) {
        this(eventDate, locationId, entryType, notes, "", partyId, startTime, reason, mentionDjIds);
    }

    public CalendarEntry(LocalDate eventDate, String locationId, String entryType,
                         String notes, String entryPrice, String partyId, LocalTime startTime,
                         String reason, Collection<String> mentionDjIds) {
        this.eventDate = eventDate;
        this.locationId = locationId;
        this.entryType = entryType;
        this.notes = notes;
        this.entryPrice = entryPrice;
        this.partyId = partyId;
        this.startTime = startTime;
        this.reason = reason;
        this.mentionDjIds = new LinkedHashSet<>(mentionDjIds);
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

    public LocalDate getEventDate() {
        return eventDate;
    }

    public String getLocationId() {
        return locationId;
    }

    public String getEntryType() {
        return entryType;
    }

    public String getNotes() {
        return notes;
    }

    public String getPartyId() {
        return partyId;
    }

    public String getEntryPrice() {
        return entryPrice;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public String getReason() {
        return reason;
    }

    public Set<String> getMentionDjIds() {
        return mentionDjIds;
    }

    public String getEventFlyerMediaId() {
        return eventFlyerMediaId;
    }

    public void setEventFlyerMediaId(String eventFlyerMediaId) {
        this.eventFlyerMediaId = eventFlyerMediaId;
    }

    public void update(LocalDate eventDate, String locationId, String entryType,
                       String notes, String entryPrice, String partyId, LocalTime startTime,
                       String reason, Collection<String> mentionDjIds) {
        this.eventDate = eventDate;
        this.locationId = locationId;
        this.entryType = entryType;
        this.notes = notes;
        this.entryPrice = entryPrice;
        this.partyId = partyId;
        this.startTime = startTime;
        this.reason = reason;
        this.mentionDjIds.clear();
        this.mentionDjIds.addAll(mentionDjIds);
    }
}
