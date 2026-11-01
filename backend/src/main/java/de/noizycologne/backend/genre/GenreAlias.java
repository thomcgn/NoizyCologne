package de.noizycologne.backend.genre;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "genre_aliases")
public class GenreAlias {
    @Id
    @Column(nullable = false, length = 100)
    private String id;

    @Column(name = "genre_id", nullable = false, length = 100)
    private String genreId;

    @Column(name = "alias_value", nullable = false, length = 100)
    private String value;

    protected GenreAlias() { }

    public GenreAlias(String genreId, String value) {
        this.genreId = genreId;
        this.value = value;
    }

    @PrePersist
    void assignId() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public String getGenreId() { return genreId; }
    public String getValue() { return value; }

    public void update(String value) { this.value = value; }
}
