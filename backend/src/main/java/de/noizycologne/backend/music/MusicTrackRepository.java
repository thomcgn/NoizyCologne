package de.noizycologne.backend.music;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MusicTrackRepository extends JpaRepository<MusicTrack, String> {
}
