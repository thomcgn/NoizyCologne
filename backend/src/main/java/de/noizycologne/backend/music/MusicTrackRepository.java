package de.noizycologne.backend.music;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MusicTrackRepository extends JpaRepository<MusicTrack, String> {

    @Override
    @EntityGraph(attributePaths = {"genres", "partySeriesIds"})
    @NonNull List<MusicTrack> findAll();
}
