package de.noizycologne.backend.instagram;

/** Publishing-Zwischenzustand des Graph-API-Containers (kein Instagram-App-Entwurf). */
public enum ContainerStatus {
    IN_PROGRESS, FINISHED, FAILED, EXPIRED;

    public boolean readyForPublish() {
        return this == FINISHED;
    }
}
