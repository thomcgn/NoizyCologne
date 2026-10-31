package de.noizycologne.backend.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    @Column(nullable = false, length = 100)
    private String id;

    @Column(name = "storage_path", nullable = false, unique = true, length = 500)
    private String storagePath;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "media_type", nullable = false, length = 50)
    private String mediaType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected MediaAsset() {
    }

    public MediaAsset(String id, String storagePath, String originalFilename, String mediaType,
                      long fileSize, String checksumSha256) {
        this.id = id;
        this.storagePath = storagePath;
        this.originalFilename = originalFilename;
        this.mediaType = mediaType;
        this.fileSize = fileSize;
        this.checksumSha256 = checksumSha256;
    }

    @PrePersist
    void assignDefaults() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public String getStoragePath() { return storagePath; }
    public String getOriginalFilename() { return originalFilename; }
    public String getMediaType() { return mediaType; }
    public long getFileSize() { return fileSize; }
}
