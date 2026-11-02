package de.noizycologne.backend.music;

import de.noizycologne.backend.calendar.CalendarEntry;
import de.noizycologne.backend.calendar.CalendarEntryRepository;
import de.noizycologne.backend.editorial.EditorialPostDraftRepository;
import de.noizycologne.backend.party.PartySeriesRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class MusicTrackService {

    private final MusicTrackRepository trackRepository;
    private final PartySeriesRepository partySeriesRepository;
    private final CalendarEntryRepository calendarEntryRepository;
    private final EditorialPostDraftRepository draftRepository;

    public MusicTrackService(MusicTrackRepository trackRepository,
                             PartySeriesRepository partySeriesRepository,
                             CalendarEntryRepository calendarEntryRepository,
                             EditorialPostDraftRepository draftRepository) {
        this.trackRepository = trackRepository;
        this.partySeriesRepository = partySeriesRepository;
        this.calendarEntryRepository = calendarEntryRepository;
        this.draftRepository = draftRepository;
    }

    @Transactional(readOnly = true)
    public List<MusicTrackResponse> getSuggestions(String calendarEntryId) {
        SuggestionContext context = calendarEntryId == null || calendarEntryId.isBlank()
                ? SuggestionContext.empty()
                : contextFor(calendarEntryId);
        return trackRepository.findAll().stream()
                .map(track -> score(track, context))
                .sorted((left, right) -> Integer.compare(right.score(), left.score()))
                .map(scored -> MusicTrackResponse.from(scored.track(), scored.score(), scored.reasons()))
                .toList();
    }

    @Transactional
    public MusicTrackResponse create(MusicTrackRequest request) {
        validatePartyIds(request.partySeriesIds());
        MusicTrack track = new MusicTrack(request.artist(), request.title(), request.genres(),
                request.partySeriesIds(), request.youtubeVideoId(), request.spotifyTrackUrl(),
                request.previewStartSeconds(), request.preferredPositionSeconds());
        return MusicTrackResponse.from(trackRepository.save(track), 0, List.of());
    }

    @Transactional
    public MusicTrackResponse update(String id, MusicTrackRequest request) {
        MusicTrack track = trackRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Song nicht gefunden"));
        validatePartyIds(request.partySeriesIds());
        track.update(request.artist(), request.title(), request.genres(), request.partySeriesIds(),
                request.youtubeVideoId(), request.spotifyTrackUrl(), request.previewStartSeconds(),
                request.preferredPositionSeconds());
        return MusicTrackResponse.from(trackRepository.save(track), 0, List.of());
    }

    @Transactional
    public void markUsed(String id) {
        MusicTrack track = trackRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Song nicht gefunden"));
        track.markUsed(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void validatePartyIds(Set<String> partyIds) {
        if (partyIds.isEmpty()) return;
        if (partySeriesRepository.findAllById(partyIds).size() != partyIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mindestens eine Partyreihe wurde nicht gefunden");
        }
    }

    private SuggestionContext contextFor(String calendarEntryId) {
        CalendarEntry entry = calendarEntryRepository.findById(calendarEntryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kalendereintrag nicht gefunden"));
        if (!"event".equals(entry.getEntryType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Musikvorschläge sind nur für Veranstaltungen möglich");
        }
        Set<String> genres = draftRepository.findByCalendarEntryId(calendarEntryId)
                .map(draft -> draft.getGenres().stream().map(genre -> genre.toLowerCase()).collect(java.util.stream.Collectors.toSet()))
                .orElseGet(() -> Set.of());
        return new SuggestionContext(entry.getPartyId(), genres);
    }

    private ScoredTrack score(MusicTrack track, SuggestionContext context) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        if (context.partyId() != null && track.getPartySeriesIds().contains(context.partyId())) {
            score += 100;
            reasons.add("Partyreihen-Favorit");
        }
        Set<String> trackGenres = new HashSet<>();
        track.getGenres().forEach(genre -> trackGenres.add(genre.toLowerCase()));
        long genreMatches = trackGenres.stream().filter(genre -> context.genres().contains(genre)).count();
        if (genreMatches > 0) {
            score += (int) genreMatches * 25;
            reasons.add("Bestätigte Event-Genres");
        }
        if (track.getLastUsedAt() != null) {
            long days = ChronoUnit.DAYS.between(track.getLastUsedAt(), OffsetDateTime.now(ZoneOffset.UTC));
            if (days < 14) {
                score -= 30;
                reasons.add("Kürzlich redaktionell verwendet");
            } else {
                reasons.add("Frühere redaktionelle Auswahl");
            }
        }
        return new ScoredTrack(track, score, reasons);
    }

    private record SuggestionContext(String partyId, Set<String> genres) {
        private static SuggestionContext empty() { return new SuggestionContext(null, Set.of()); }
    }

    private record ScoredTrack(MusicTrack track, int score, List<String> reasons) { }
}
