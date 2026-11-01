package de.noizycologne.backend.editorial;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EditorialPostDraftRepository extends JpaRepository<EditorialPostDraft, String> {
    Optional<EditorialPostDraft> findByCalendarEntryId(String calendarEntryId);
}
