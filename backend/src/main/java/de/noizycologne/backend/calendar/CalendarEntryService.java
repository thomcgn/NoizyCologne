package de.noizycologne.backend.calendar;

import de.noizycologne.backend.actor.Actor;
import de.noizycologne.backend.actor.ActorRepository;
import de.noizycologne.backend.media.MediaAssetRepository;
import de.noizycologne.backend.media.PartyFlyerResponse;
import de.noizycologne.backend.party.PartySeriesRepository;
import de.noizycologne.backend.venue.VenueRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class CalendarEntryService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    private final CalendarEntryRepository calendarEntryRepository;
    private final VenueRepository venueRepository;
    private final PartySeriesRepository partySeriesRepository;
    private final ActorRepository actorRepository;
    private final MediaAssetRepository mediaAssetRepository;

    public CalendarEntryService(CalendarEntryRepository calendarEntryRepository,
                                VenueRepository venueRepository,
                                PartySeriesRepository partySeriesRepository,
                                ActorRepository actorRepository,
                                MediaAssetRepository mediaAssetRepository) {
        this.calendarEntryRepository = calendarEntryRepository;
        this.venueRepository = venueRepository;
        this.partySeriesRepository = partySeriesRepository;
        this.actorRepository = actorRepository;
        this.mediaAssetRepository = mediaAssetRepository;
    }

    @Transactional(readOnly = true)
    public List<CalendarEntryResponse> getEntries() {
        return calendarEntryRepository.findAll().stream()
                .sorted((left, right) -> {
                    int dateComparison = left.getEventDate().compareTo(right.getEventDate());
                    if (dateComparison != 0) return dateComparison;
                    int timeComparison = Comparator.nullsLast(Comparator.<LocalTime>naturalOrder())
                            .compare(left.getStartTime(), right.getStartTime());
                    if (timeComparison != 0) return timeComparison;
                    return left.getId().compareTo(right.getId());
                })
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CalendarEntryResponse createEntry(CalendarEntryRequest request) {
        ValidatedEntry validated = validate(request);
        CalendarEntry entry = new CalendarEntry(
                validated.date(),
                validated.locationId(),
                validated.type(),
                validated.notes(),
                validated.partyId(),
                validated.startTime(),
                validated.reason(),
                validated.mentionDjIds()
        );
        return toResponse(calendarEntryRepository.save(entry));
    }

    @Transactional
    public CalendarEntryResponse updateEntry(String id, CalendarEntryRequest request) {
        ValidatedEntry validated = validate(request);
        CalendarEntry entry = calendarEntryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kalendereintrag nicht gefunden"));
        entry.update(
                validated.date(),
                validated.locationId(),
                validated.type(),
                validated.notes(),
                validated.partyId(),
                validated.startTime(),
                validated.reason(),
                validated.mentionDjIds()
        );
        return toResponse(calendarEntryRepository.save(entry));
    }

    @Transactional
    public void deleteEntry(String id) {
        CalendarEntry entry = calendarEntryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kalendereintrag nicht gefunden"));
        calendarEntryRepository.delete(entry);
    }

    private ValidatedEntry validate(CalendarEntryRequest request) {
        LocalDate date = parseDate(request.date());
        String type = request.type().trim();
        String locationId = request.locationId().trim();
        String notes = request.notes().trim();
        String partyId = optional(request.partyId());
        String startTimeValue = optional(request.startTime());
        String reason = optional(request.reason());
        Set<String> mentionDjIds = new HashSet<>(request.mentionDjIds());

        if (!type.equals("event") && !type.equals("closure")) {
            throw badRequest("type muss event oder closure sein");
        }
        if (!venueRepository.existsById(locationId)) {
            throw badRequest("Die Location-ID existiert nicht");
        }

        LocalTime startTime = startTimeValue == null ? null : parseTime(startTimeValue);
        if (type.equals("event")) {
            if (partyId == null || !partySeriesRepository.existsById(partyId)) {
                throw badRequest("Die Partyreihen-ID existiert nicht");
            }
            if (reason != null) {
                throw badRequest("Ein event darf keinen Sperrgrund enthalten");
            }
            validateDjMentions(mentionDjIds);
        } else {
            if (partyId != null) {
                throw badRequest("Eine closure darf keine Partyreihe enthalten");
            }
            if (startTime != null) {
                throw badRequest("Eine closure darf keine Startzeit enthalten");
            }
            if (!mentionDjIds.isEmpty()) {
                throw badRequest("Eine closure darf keine DJ-Mentions enthalten");
            }
            if (reason == null) {
                throw badRequest("Eine closure benötigt einen Grund");
            }
        }

        return new ValidatedEntry(date, locationId, type, notes, partyId, startTime, reason, mentionDjIds);
    }

    private void validateDjMentions(Set<String> mentionDjIds) {
        if (mentionDjIds.isEmpty()) {
            return;
        }

        List<Actor> actors = actorRepository.findAllById(mentionDjIds);
        if (actors.size() != mentionDjIds.size()) {
            throw badRequest("Mindestens eine Mention-Akteur-ID existiert nicht");
        }
        for (Actor actor : actors) {
            if (!actor.getRoles().contains("dj")) {
                throw badRequest("DJ-Mentions dürfen nur auf Akteure mit DJ-Rolle verweisen");
            }
            if (actor.getInstagram() == null || actor.getInstagram().isBlank()) {
                throw badRequest("Jeder erwähnte DJ benötigt einen Instagram-Handle");
            }
        }
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim(), DATE_FORMAT);
        } catch (DateTimeParseException exception) {
            throw badRequest("date muss im Format YYYY-MM-DD vorliegen");
        }
    }

    private LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value, TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            throw badRequest("startTime muss im Format HH:mm vorliegen");
        }
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private CalendarEntryResponse toResponse(CalendarEntry entry) {
        PartyFlyerResponse flyer = null;
        if (entry.getEventFlyerMediaId() != null) {
            flyer = mediaAssetRepository.findById(entry.getEventFlyerMediaId())
                    .map(asset -> new PartyFlyerResponse(
                            asset.getId(),
                            asset.getOriginalFilename(),
                            asset.getMediaType(),
                            asset.getFileSize(),
                            "/api/v1/calendar-entries/" + entry.getId() + "/flyer"
                    ))
                    .orElse(null);
        }
        return CalendarEntryResponse.from(entry, flyer);
    }

    private record ValidatedEntry(
            LocalDate date,
            String locationId,
            String type,
            String notes,
            String partyId,
            LocalTime startTime,
            String reason,
            Set<String> mentionDjIds
    ) {
    }
}
