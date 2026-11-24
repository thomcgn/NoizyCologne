package de.noizycologne.backend.instagram;

/** Statuswerte aus docs/PROJECT.md, Kapitel 5.2 (hier auf Publishing beschränkt). */
public enum PublicationStatus {
    DRAFT, SCHEDULED, BLOCKED, CLAIMED, CONTAINER_CREATED, READY_FOR_PUBLISH,
    PUBLISHING, PUBLISHED, UNKNOWN_REMOTE_RESULT, FAILED, CANCELLED_LOCALLY, REMOVED_EXTERNALLY
}
