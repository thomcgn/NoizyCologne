package de.noizycologne.backend.editorial;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EditorialPostDraftRepository extends JpaRepository<EditorialPostDraft, String> {

    @EntityGraph(attributePaths = {"mentionActorIds", "genres"})
    Optional<EditorialPostDraft> findByCalendarEntryId(String calendarEntryId);

    @Override
    @EntityGraph(attributePaths = {"mentionActorIds", "genres"})
    @NonNull List<EditorialPostDraft> findAll();

    @Override
    @EntityGraph(attributePaths = {"mentionActorIds", "genres"})
    @NonNull Optional<EditorialPostDraft> findById(String id);
}
