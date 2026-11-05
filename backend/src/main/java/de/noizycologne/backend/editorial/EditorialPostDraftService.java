package de.noizycologne.backend.editorial;

import de.noizycologne.backend.actor.Actor;
import de.noizycologne.backend.actor.ActorRepository;
import de.noizycologne.backend.calendar.CalendarEntry;
import de.noizycologne.backend.calendar.CalendarEntryRepository;
import de.noizycologne.backend.media.MediaAssetRepository;
import de.noizycologne.backend.music.MusicTrack;
import de.noizycologne.backend.music.MusicTrackRepository;
import de.noizycologne.backend.party.PartySeriesRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class EditorialPostDraftService {

    @org.springframework.beans.factory.annotation.Autowired private de.noizycologne.backend.cancellation.PublishEligibilityService eligibility;
    @org.springframework.beans.factory.annotation.Autowired private de.noizycologne.backend.discovery.DiscoveryStore discoveryStore;

    private EditorialPostDraftResponse response(EditorialPostDraft draft) { return EditorialPostDraftResponse.from(draft,eligibility.blockers(draft.getCalendarEntryId())); }
    private void lockEditorial() { discoveryStore.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)"); }

    private static final Set<String> STATUSES = Set.of("DRAFT", "READY_FOR_REVIEW", "APPROVED");

    private final EditorialPostDraftRepository draftRepository;
    private final CalendarEntryRepository calendarEntryRepository;
    private final ActorRepository actorRepository;
    private final PartySeriesRepository partySeriesRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final MusicTrackRepository musicTrackRepository;

    public EditorialPostDraftService(EditorialPostDraftRepository draftRepository,
                                     CalendarEntryRepository calendarEntryRepository,
                                     ActorRepository actorRepository,
                                     PartySeriesRepository partySeriesRepository,
                                     MediaAssetRepository mediaAssetRepository,
                                     MusicTrackRepository musicTrackRepository) {
        this.draftRepository = draftRepository;
        this.calendarEntryRepository = calendarEntryRepository;
        this.actorRepository = actorRepository;
        this.partySeriesRepository = partySeriesRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.musicTrackRepository = musicTrackRepository;
    }

    @Transactional(readOnly = true)
    public List<EditorialPostDraftResponse> getDrafts() {
        return draftRepository.findAll().stream().map(draft -> response(draft)).toList();
    }

    @Transactional
    public EditorialPostDraftResponse create(String calendarEntryId, EditorialPostDraftRequest request) {
        lockEditorial();
        CalendarEntry entry = findEvent(calendarEntryId);
        if (draftRepository.findByCalendarEntryId(calendarEntryId).isPresent()) {
            throw conflict("Für diese Veranstaltung existiert bereits ein Redaktionsentwurf");
        }
        MusicTrack musicTrack = validate(entry, request);
        Set<String> genres = normalizedGenres(request, partyStandardGenres(entry));
        EditorialPostDraft draft = new EditorialPostDraft(calendarEntryId, request.caption().trim(),
                request.hashtags().trim(), request.status(), request.mentionActorIds(), genres);
        draft.update(request.caption().trim(), request.hashtags().trim(), request.status(),
                request.mentionActorIds(), genres, musicTrack == null ? null : musicTrack.getId());
        if (musicTrack != null) musicTrack.markUsed(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        if ("APPROVED".equals(request.status())) draft.confirmReview();
        return response(draftRepository.save(draft));
    }

    @Transactional
    public EditorialPostDraftResponse update(String id, EditorialPostDraftRequest request) {
        lockEditorial();
        EditorialPostDraft draft = draftRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Redaktionsentwurf nicht gefunden"));
        CalendarEntry entry = findEvent(draft.getCalendarEntryId());
        MusicTrack musicTrack = validate(entry, request);
        draft.update(request.caption().trim(), request.hashtags().trim(), request.status(), request.mentionActorIds(),
                normalizedGenres(request, Set.of()), musicTrack == null ? null : musicTrack.getId());
        if (musicTrack != null) musicTrack.markUsed(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        if ("APPROVED".equals(request.status())) draft.confirmReview();
        return response(draftRepository.save(draft));
    }

    private CalendarEntry findEvent(String id) {
        CalendarEntry entry = calendarEntryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kalendereintrag nicht gefunden"));
        if (!"event".equals(entry.getEntryType())) {
            throw badRequest("Redaktionsentwürfe sind nur für Veranstaltungen möglich");
        }
        return entry;
    }

    private MusicTrack validate(CalendarEntry entry, EditorialPostDraftRequest request) {
        if (!STATUSES.contains(request.status())) {
            throw badRequest("Unbekannter Redaktionsstatus");
        }
        if (request.genres().stream().anyMatch(genre -> genre.trim().isEmpty())) {
            throw badRequest("Genres dürfen nicht leer sein");
        }
        Set<String> mentionIds = request.mentionActorIds();
        if (!entry.getMentionDjIds().containsAll(mentionIds)) {
            throw badRequest("Jede Mention muss im Kalendereintrag als DJ-Mention ausgewählt sein");
        }
        List<Actor> actors = actorRepository.findAllById(mentionIds);
        if (actors.size() != mentionIds.size() || actors.stream().anyMatch(actor -> !actor.getRoles().contains("dj"))) {
            throw badRequest("Jede Mention muss auf einen Akteur mit DJ-Rolle verweisen");
        }
        if ("APPROVED".equals(request.status())) {
            eligibility.requireOriginalApproval(entry.getId());
            if (request.caption().isBlank()) {
                throw badRequest("Ein freizugebender Entwurf benötigt eine Caption");
            }
            if (!hasFlyer(entry)) {
                throw badRequest("Ein freizugebender Entwurf benötigt einen Dauer- oder Event-Flyer");
            }
        }
        if (request.musicTrackId() == null) return null;
        return musicTrackRepository.findById(request.musicTrackId())
                .orElseThrow(() -> badRequest("Der ausgewählte Song wurde nicht gefunden"));
    }

    private Set<String> partyStandardGenres(CalendarEntry entry) {
        if (entry.getPartyId() == null) return Set.of();
        return partySeriesRepository.findById(entry.getPartyId())
                .map(party -> party.getStandardGenres())
                .orElse(Set.of());
    }

    private Set<String> normalizedGenres(EditorialPostDraftRequest request, Set<String> defaults) {
        Set<String> genres = new LinkedHashSet<>();
        defaults.forEach(genre -> genres.add(genre.trim()));
        request.genres().forEach(genre -> genres.add(genre.trim()));
        return genres;
    }

    private boolean hasFlyer(CalendarEntry entry) {
        if (entry.getEventFlyerMediaId() != null && mediaAssetRepository.existsById(entry.getEventFlyerMediaId())) {
            return true;
        }
        return entry.getPartyId() != null && partySeriesRepository.findById(entry.getPartyId())
                .map(party -> party.getDefaultFlyerMediaId())
                .filter(mediaId -> mediaAssetRepository.existsById(mediaId))
                .isPresent();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
