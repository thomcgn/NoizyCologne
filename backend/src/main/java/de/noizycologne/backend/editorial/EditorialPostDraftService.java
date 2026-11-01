package de.noizycologne.backend.editorial;

import de.noizycologne.backend.actor.Actor;
import de.noizycologne.backend.actor.ActorRepository;
import de.noizycologne.backend.calendar.CalendarEntry;
import de.noizycologne.backend.calendar.CalendarEntryRepository;
import de.noizycologne.backend.media.MediaAssetRepository;
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

    private static final Set<String> STATUSES = Set.of("DRAFT", "READY_FOR_REVIEW", "APPROVED");

    private final EditorialPostDraftRepository draftRepository;
    private final CalendarEntryRepository calendarEntryRepository;
    private final ActorRepository actorRepository;
    private final PartySeriesRepository partySeriesRepository;
    private final MediaAssetRepository mediaAssetRepository;

    public EditorialPostDraftService(EditorialPostDraftRepository draftRepository,
                                     CalendarEntryRepository calendarEntryRepository,
                                     ActorRepository actorRepository,
                                     PartySeriesRepository partySeriesRepository,
                                     MediaAssetRepository mediaAssetRepository) {
        this.draftRepository = draftRepository;
        this.calendarEntryRepository = calendarEntryRepository;
        this.actorRepository = actorRepository;
        this.partySeriesRepository = partySeriesRepository;
        this.mediaAssetRepository = mediaAssetRepository;
    }

    @Transactional(readOnly = true)
    public List<EditorialPostDraftResponse> getDrafts() {
        return draftRepository.findAll().stream().map(EditorialPostDraftResponse::from).toList();
    }

    @Transactional
    public EditorialPostDraftResponse create(String calendarEntryId, EditorialPostDraftRequest request) {
        CalendarEntry entry = findEvent(calendarEntryId);
        if (draftRepository.findByCalendarEntryId(calendarEntryId).isPresent()) {
            throw conflict("Für diese Veranstaltung existiert bereits ein Redaktionsentwurf");
        }
        validate(entry, request);
        EditorialPostDraft draft = new EditorialPostDraft(calendarEntryId, request.caption().trim(),
                request.hashtags().trim(), request.status(), request.mentionActorIds(), normalizedGenres(request));
        return EditorialPostDraftResponse.from(draftRepository.save(draft));
    }

    @Transactional
    public EditorialPostDraftResponse update(String id, EditorialPostDraftRequest request) {
        EditorialPostDraft draft = draftRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Redaktionsentwurf nicht gefunden"));
        CalendarEntry entry = findEvent(draft.getCalendarEntryId());
        validate(entry, request);
        draft.update(request.caption().trim(), request.hashtags().trim(), request.status(), request.mentionActorIds(), normalizedGenres(request));
        return EditorialPostDraftResponse.from(draftRepository.save(draft));
    }

    private CalendarEntry findEvent(String id) {
        CalendarEntry entry = calendarEntryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kalendereintrag nicht gefunden"));
        if (!"event".equals(entry.getEntryType())) {
            throw badRequest("Redaktionsentwürfe sind nur für Veranstaltungen möglich");
        }
        return entry;
    }

    private void validate(CalendarEntry entry, EditorialPostDraftRequest request) {
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
            if (request.caption().isBlank()) {
                throw badRequest("Ein freizugebender Entwurf benötigt eine Caption");
            }
            if (!hasFlyer(entry)) {
                throw badRequest("Ein freizugebender Entwurf benötigt einen Dauer- oder Event-Flyer");
            }
        }
    }

    private Set<String> normalizedGenres(EditorialPostDraftRequest request) {
        Set<String> genres = new LinkedHashSet<>();
        request.genres().forEach(genre -> genres.add(genre.trim()));
        return genres;
    }

    private boolean hasFlyer(CalendarEntry entry) {
        if (entry.getEventFlyerMediaId() != null && mediaAssetRepository.existsById(entry.getEventFlyerMediaId())) {
            return true;
        }
        return entry.getPartyId() != null && partySeriesRepository.findById(entry.getPartyId())
                .map(party -> party.getDefaultFlyerMediaId())
                .filter(mediaAssetRepository::existsById)
                .isPresent();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
