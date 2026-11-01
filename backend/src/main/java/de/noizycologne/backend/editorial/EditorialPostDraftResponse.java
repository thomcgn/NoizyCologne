package de.noizycologne.backend.editorial;

import java.util.List;

public record EditorialPostDraftResponse(
        String id,
        String calendarEntryId,
        String caption,
        String hashtags,
        List<String> mentionActorIds,
        String status
) {
    public static EditorialPostDraftResponse from(EditorialPostDraft draft) {
        return new EditorialPostDraftResponse(
                draft.getId(),
                draft.getCalendarEntryId(),
                draft.getCaption(),
                draft.getHashtags(),
                draft.getMentionActorIds().stream().sorted().toList(),
                draft.getStatus()
        );
    }
}
