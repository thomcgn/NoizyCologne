package de.noizycologne.backend.party;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "party_series")
public class PartySeries {

    @Id
    @Column(nullable = false, length = 100)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 30)
    private String instagram;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 7)
    private String color;

    @Column(name = "dj_mention_default", nullable = false, length = 20)
    private String djMentionDefault;

    @Column(name = "recurrence_kind", length = 20)
    private String recurrenceKind;

    @Column(name = "recurrence_weekday")
    private Short recurrenceWeekday;

    @Column(name = "recurrence_occurrence")
    private Short recurrenceOccurrence;

    @Column(name = "recurrence_description")
    private String recurrenceDescription;

    @Column(name = "default_flyer_media_id", length = 100)
    private String defaultFlyerMediaId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "party_djs", joinColumns = @JoinColumn(name = "party_id"))
    private Set<PartyDjAssignment> djAssignments = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "party_organizers", joinColumns = @JoinColumn(name = "party_id"))
    @Column(name = "actor_id", nullable = false, length = 100)
    private Set<String> organizerIds = new LinkedHashSet<>();

    protected PartySeries() {
    }

    public PartySeries(String name, String instagram, String color,
                       String djMentionDefault, String recurrenceKind,
                       Short recurrenceWeekday, Short recurrenceOccurrence,
                       String recurrenceDescription,
                       Collection<PartyDjAssignment> djAssignments,
                       Collection<String> organizerIds) {
        this.name = name;
        this.instagram = instagram;
        this.color = color;
        this.djMentionDefault = djMentionDefault;
        this.recurrenceKind = recurrenceKind;
        this.recurrenceWeekday = recurrenceWeekday;
        this.recurrenceOccurrence = recurrenceOccurrence;
        this.recurrenceDescription = recurrenceDescription;
        this.djAssignments = new LinkedHashSet<>(djAssignments);
        this.organizerIds = new LinkedHashSet<>(organizerIds);
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

    public String getInstagram() {
        return instagram;
    }

    public String getColor() {
        return color;
    }

    public String getDjMentionDefault() {
        return djMentionDefault;
    }

    public String getRecurrenceKind() {
        return recurrenceKind;
    }

    public Short getRecurrenceWeekday() {
        return recurrenceWeekday;
    }

    public Short getRecurrenceOccurrence() {
        return recurrenceOccurrence;
    }

    public String getRecurrenceDescription() {
        return recurrenceDescription;
    }

    public String getDefaultFlyerMediaId() {
        return defaultFlyerMediaId;
    }

    public void setDefaultFlyerMediaId(String defaultFlyerMediaId) {
        this.defaultFlyerMediaId = defaultFlyerMediaId;
    }

    public Set<PartyDjAssignment> getDjAssignments() {
        return djAssignments;
    }

    public Set<String> getOrganizerIds() {
        return organizerIds;
    }

    public void update(String name, String instagram, String color,
                       String djMentionDefault, String recurrenceKind,
                       Short recurrenceWeekday, Short recurrenceOccurrence,
                       String recurrenceDescription,
                       Collection<PartyDjAssignment> djAssignments,
                       Collection<String> organizerIds) {
        this.name = name;
        this.instagram = instagram;
        this.color = color;
        this.djMentionDefault = djMentionDefault;
        this.recurrenceKind = recurrenceKind;
        this.recurrenceWeekday = recurrenceWeekday;
        this.recurrenceOccurrence = recurrenceOccurrence;
        this.recurrenceDescription = recurrenceDescription;
        this.djAssignments.clear();
        this.djAssignments.addAll(djAssignments);
        this.organizerIds.clear();
        this.organizerIds.addAll(organizerIds);
    }
}
