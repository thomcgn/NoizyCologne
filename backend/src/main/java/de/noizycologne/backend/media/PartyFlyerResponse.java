package de.noizycologne.backend.media;

public record PartyFlyerResponse(
        String id,
        String originalFilename,
        String mediaType,
        long fileSize,
        String url
) {
}
