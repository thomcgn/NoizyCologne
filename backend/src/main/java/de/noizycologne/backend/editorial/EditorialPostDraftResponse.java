package de.noizycologne.backend.editorial;

import java.util.List;

public record EditorialPostDraftResponse(
        String id,
        String calendarEntryId,
        String caption,
        String hashtags,
        List<String> mentionActorIds,
        List<String> genres,
        String status,
        String musicTrackId,
        boolean approvalNeedsReview,
        List<String> publicationBlockers
) {
    public static EditorialPostDraftResponse from(EditorialPostDraft draft) { return from(draft,List.of()); }
    public static EditorialPostDraftResponse from(EditorialPostDraft draft,List<String> blockers) {
        return new EditorialPostDraftResponse(
                draft.getId(),
                draft.getCalendarEntryId(),
                draft.getCaption(),
                draft.getHashtags(),
                draft.getMentionActorIds().stream().sorted().toList(),
                draft.getGenres().stream().sorted().toList(),
                draft.getStatus(),
                draft.getMusicTrackId(),
                draft.isApprovalNeedsReview(),
                blockers
        );
    }
}
