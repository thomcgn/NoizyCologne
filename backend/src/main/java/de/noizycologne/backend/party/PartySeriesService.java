package de.noizycologne.backend.party;

import de.noizycologne.backend.actor.Actor;
import de.noizycologne.backend.actor.ActorRepository;
import de.noizycologne.backend.calendar.CalendarEntryRepository;
import de.noizycologne.backend.media.MediaAssetRepository;
import de.noizycologne.backend.media.PartyFlyerResponse;
import de.noizycologne.backend.genre.GenreRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PartySeriesService {

    private static final Set<String> RECURRENCE_KINDS = Set.of("weekly", "monthly_weekday", "other");
    private static final Set<Integer> MONTHLY_OCCURRENCES = Set.of(-1, 1, 2, 3, 4);

    private final PartySeriesRepository partySeriesRepository;
    private final ActorRepository actorRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final GenreRepository genreRepository;
    private final CalendarEntryRepository calendarEntryRepository;

    public PartySeriesService(PartySeriesRepository partySeriesRepository, ActorRepository actorRepository,
                              MediaAssetRepository mediaAssetRepository, GenreRepository genreRepository,
                              CalendarEntryRepository calendarEntryRepository) {
        this.partySeriesRepository = partySeriesRepository;
        this.actorRepository = actorRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.genreRepository = genreRepository;
        this.calendarEntryRepository = calendarEntryRepository;
    }

    @Transactional(readOnly = true)
    public List<PartySeriesResponse> getParties() {
        return partySeriesRepository.findAll().stream()
                .map(party -> toResponse(party))
                .toList();
    }

    @Transactional
    public PartySeriesResponse createParty(PartySeriesRequest request) {
        validateRequest(request);
        ensureNameAvailable(request.name(), null);
        validateActorReferences(request);
        validateStandardGenres(request.standardGenres());

        PartySeries party = new PartySeries(
                request.name().trim(),
                request.instagram(),
                request.color(),
                request.djMentionDefault(),
                recurrenceKind(request),
                recurrenceWeekday(request),
                recurrenceOccurrence(request),
                recurrenceDescription(request),
                djAssignments(request),
                request.organizerIds(),
                request.standardGenres()
        );
        return toResponse(partySeriesRepository.save(party));
    }

    @Transactional
    public PartySeriesResponse updateParty(String id, PartySeriesRequest request) {
        validateRequest(request);
        PartySeries party = partySeriesRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Partyreihe nicht gefunden"));
        ensureNameAvailable(request.name(), id);
        validateActorReferences(request);
        validateStandardGenres(request.standardGenres());

        party.update(
                request.name().trim(),
                request.instagram(),
                request.color(),
                request.djMentionDefault(),
                recurrenceKind(request),
                recurrenceWeekday(request),
                recurrenceOccurrence(request),
                recurrenceDescription(request),
                djAssignments(request),
                request.organizerIds(),
                request.standardGenres()
        );
        return toResponse(partySeriesRepository.save(party));
    }

    @Transactional
    public void deleteParty(String id) {
        PartySeries party = partySeriesRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Partyreihe nicht gefunden"));
        if (calendarEntryRepository.countByPartyId(id) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Die Partyreihe wird noch von Kalendereinträgen verwendet und kann nicht gelöscht werden");
        }
        partySeriesRepository.delete(party);
    }

    private PartySeriesResponse toResponse(PartySeries party) {
        PartyFlyerResponse flyer = null;
        var mediaId = party.getDefaultFlyerMediaId();
        if (mediaId != null) {
            var asset = mediaAssetRepository.findById(mediaId);
            if (asset.isPresent()) {
                var media = asset.get();
                flyer = new PartyFlyerResponse(media.getId(), media.getOriginalFilename(),
                        media.getMediaType(), media.getFileSize(),
                        "/api/v1/parties/" + party.getId() + "/default-flyer");
            }
        }
        return PartySeriesResponse.from(party, flyer);
    }

    private void validateRequest(PartySeriesRequest request) {
        if (request.recurrence() == null) {
            return;
        }

        PartyRecurrenceRequest recurrence = request.recurrence();
        if (!RECURRENCE_KINDS.contains(recurrence.kind())) {
            throw badRequest("recurrence.kind muss weekly, monthly_weekday oder other sein");
        }

        if ("weekly".equals(recurrence.kind())) {
            requireWeekday(recurrence.weekday());
            if (recurrence.occurrence() != null || recurrence.description() != null) {
                throw badRequest("weekly darf nur weekday enthalten");
            }
        } else if ("monthly_weekday".equals(recurrence.kind())) {
            requireWeekday(recurrence.weekday());
            if (recurrence.occurrence() == null || !MONTHLY_OCCURRENCES.contains(recurrence.occurrence())) {
                throw badRequest("monthly_weekday benötigt occurrence 1-4 oder -1");
            }
            if (recurrence.description() != null) {
                throw badRequest("monthly_weekday darf keine Beschreibung enthalten");
            }
        } else {
            if (recurrence.weekday() != null || recurrence.occurrence() != null
                    || recurrence.description() == null || recurrence.description().isBlank()) {
                throw badRequest("other benötigt eine nichtleere Beschreibung");
            }
        }
    }

    private void requireWeekday(Integer weekday) {
        if (weekday == null || weekday < 0 || weekday > 6) {
            throw badRequest("weekday muss zwischen 0 und 6 liegen");
        }
    }

    private void validateActorReferences(PartySeriesRequest request) {
        Set<String> residentIds = request.residentDjIds();
        Set<String> otherIds = request.otherDjIds();
        Set<String> overlappingDjs = new HashSet<>(residentIds);
        overlappingDjs.retainAll(otherIds);
        if (!overlappingDjs.isEmpty()) {
            throw badRequest("Ein DJ darf nicht gleichzeitig Resident und weiterer DJ sein");
        }

        Set<String> allIds = new LinkedHashSet<>();
        allIds.addAll(residentIds);
        allIds.addAll(otherIds);
        allIds.addAll(request.organizerIds());

        List<Actor> actors = actorRepository.findAllById(allIds);
        if (actors.size() != allIds.size()) {
            throw badRequest("Mindestens eine Akteur-ID existiert nicht");
        }

        Set<String> djIds = new HashSet<>();
        Set<String> organizerIds = new HashSet<>();
        for (Actor actor : actors) {
            if (actor.getRoles().contains("dj")) djIds.add(actor.getId());
            if (actor.getRoles().contains("organizer")) organizerIds.add(actor.getId());
        }

        Set<String> requestedDjIds = new HashSet<>(residentIds);
        requestedDjIds.addAll(otherIds);
        if (!djIds.containsAll(requestedDjIds)) {
            throw badRequest("Jede DJ-Zuordnung muss auf einen Akteur mit DJ-Rolle verweisen");
        }
        if (!organizerIds.containsAll(request.organizerIds())) {
            throw badRequest("Jede Veranstalter-Zuordnung muss auf einen Akteur mit Veranstalterrolle verweisen");
        }
    }

    private List<PartyDjAssignment> djAssignments(PartySeriesRequest request) {
        List<PartyDjAssignment> assignments = new ArrayList<>();
        request.residentDjIds().forEach(id -> assignments.add(new PartyDjAssignment(id, "resident")));
        request.otherDjIds().forEach(id -> assignments.add(new PartyDjAssignment(id, "other")));
        return assignments;
    }

    private void validateStandardGenres(Set<String> standardGenres) {
        if (standardGenres.isEmpty()) return;
        // The API and party_series_genres store exact display names, never genre IDs.
        var genres = genreRepository.findAllByDisplayNameInAndActiveTrue(standardGenres);
        for (String name : standardGenres.stream().sorted().toList()) {
            long matches = genres.stream().filter(genre -> genre.getDisplayName().equals(name)).count();
            if (matches == 0) throw badRequest("Unbekanntes oder inaktives Standardgenre: „" + name + "“. Bitte ein aktives Genre auswählen oder den Eintrag entfernen.");
            if (matches > 1) throw badRequest("Mehrdeutiges Standardgenre: „" + name + "“. Der Anzeigename ist mehreren aktiven Genres zugeordnet; bitte die Genreverwaltung korrigieren.");
        }
    }

    private String recurrenceKind(PartySeriesRequest request) {
        return request.recurrence() == null ? null : request.recurrence().kind();
    }

    private Short recurrenceWeekday(PartySeriesRequest request) {
        return request.recurrence() == null || request.recurrence().weekday() == null
                ? null : request.recurrence().weekday().shortValue();
    }

    private Short recurrenceOccurrence(PartySeriesRequest request) {
        return request.recurrence() == null || request.recurrence().occurrence() == null
                ? null : request.recurrence().occurrence().shortValue();
    }

    private String recurrenceDescription(PartySeriesRequest request) {
        return request.recurrence() == null || request.recurrence().description() == null
                ? null : request.recurrence().description().trim();
    }

    private void ensureNameAvailable(String name, String currentId) {
        partySeriesRepository.findByNameIgnoreCase(name.trim())
                .filter(party -> !party.getId().equals(currentId))
                .ifPresent(party -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Dieser Partyreihenname existiert bereits");
                });
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
