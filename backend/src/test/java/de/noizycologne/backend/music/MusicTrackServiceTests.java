package de.noizycologne.backend.music;

import de.noizycologne.backend.calendar.CalendarEntry;
import de.noizycologne.backend.calendar.CalendarEntryRepository;
import de.noizycologne.backend.editorial.EditorialPostDraft;
import de.noizycologne.backend.editorial.EditorialPostDraftRepository;
import de.noizycologne.backend.party.PartySeries;
import de.noizycologne.backend.party.PartySeriesRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MusicTrackServiceTests {

    private final MusicTrackRepository tracks = mock(MusicTrackRepository.class);
    private final PartySeriesRepository parties = mock(PartySeriesRepository.class);
    private final CalendarEntryRepository calendars = mock(CalendarEntryRepository.class);
    private final EditorialPostDraftRepository drafts = mock(EditorialPostDraftRepository.class);
    private final MusicTrackService service = new MusicTrackService(tracks, parties, calendars, drafts);

    private static MusicTrack track(String artist, Set<String> genres, Set<String> partyIds) {
        var track = new MusicTrack(artist, "Title", genres, partyIds, "dQw4w9WgXcQ", "", 5, 5);
        track.assignId();
        return track;
    }

    private static CalendarEntry event(String partyId, String entryType) {
        return new CalendarEntry(LocalDate.of(2027, 5, 20), "venue-1", entryType, "",
                partyId, LocalTime.of(21, 0), "", Set.of());
    }

    /** Mockito matchers are legacy APIs; keep their unannotated values at this boundary. */
    private static MusicTrack anyTrack() {
        return org.mockito.ArgumentMatchers.any();
    }

    private void stubTrackSave() {
        when(tracks.save(anyTrack())).thenAnswer(call -> {
            var saved = Objects.requireNonNull(call.getArgument(0, MusicTrack.class));
            saved.assignId();
            return saved;
        });
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void createPersistsValidTrackWithReferencedParty() {
        when(parties.findAllById(Objects.requireNonNull(Set.of("party-1")))).thenReturn(List.of(mock(PartySeries.class)));
        stubTrackSave();

        MusicTrackResponse created = service.create(new MusicTrackRequest(
                "Verified Artist", "Verified Song", Set.of("Punk"), Set.of("party-1"),
                "dQw4w9WgXcQ", "", 12, 15));

        assertThat(created.artist()).isEqualTo("Verified Artist");
        assertThat(created.title()).isEqualTo("Verified Song");
        assertThat(created.genres()).containsExactly("Punk");
        assertThat(created.partySeriesIds()).containsExactly("party-1");
        assertThat(created.previewStartSeconds()).isEqualTo(12);
        assertThat(created.preferredPositionSeconds()).isEqualTo(15);
        assertThat(created.score()).isZero();
        assertThat(created.matchReasons()).isEmpty();
        assertThat(created.lastUsedAt()).isNull();
    }

    @Test
    void createRejectsUnknownPartyReferenceBeforeSaving() {
        when(parties.findAllById(Objects.requireNonNull(Set.of("missing")))).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(new MusicTrackRequest(
                "Artist", "Title", Set.of(), Set.of("missing"), "dQw4w9WgXcQ", "", 0, 0)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
                    assertThat(error).hasMessageContaining("Partyreihe");
                });
        verify(tracks, never()).save(anyTrack());
    }

    @Test
    void createAcceptsNullCollectionsAsEmptySets() {
        stubTrackSave();

        MusicTrackResponse created = service.create(new MusicTrackRequest(
                null, null, null, null, null, null, null, null));

        assertThat(created.artist()).isEmpty();
        assertThat(created.title()).isEmpty();
        assertThat(created.genres()).isEmpty();
        assertThat(created.partySeriesIds()).isEmpty();
        assertThat(created.youtubeVideoId()).isEmpty();
        assertThat(created.spotifyTrackUrl()).isEmpty();
        assertThat(created.previewStartSeconds()).isZero();
        verifyNoInteractions(parties);
    }

    @Test
    void updateRejectsMissingIdAndReplacesPersistedValues() {
        assertStatus(() -> service.update("missing", new MusicTrackRequest(
                "Artist", "Title", Set.of(), Set.of(), "dQw4w9WgXcQ", "", 0, 0)),
                HttpStatus.NOT_FOUND.value());

        var track = track("Old Artist", Set.of("Punk"), Set.of("party-1"));
        when(tracks.findById(track.getId())).thenReturn(Optional.of(track));
        when(tracks.save(anyTrack())).thenAnswer(call -> call.getArgument(0, MusicTrack.class));

        service.update(track.getId(), new MusicTrackRequest(
                "New Artist", "New Title", Set.of("Techno"), Set.of(), "dQw4w9WgXcQ", "", 3, 3));

        assertThat(track.getArtist()).isEqualTo("New Artist");
        assertThat(track.getGenres()).containsExactly("Techno");
        assertThat(track.getPartySeriesIds()).isEmpty();
        verify(tracks).save(track);
    }

    @Test
    void markUsedRejectsMissingIdAndRecordsTimestampOtherwise() {
        assertStatus(() -> service.markUsed("missing"), HttpStatus.NOT_FOUND.value());

        var track = track("Artist", Set.of(), Set.of());
        when(tracks.findById(track.getId())).thenReturn(Optional.of(track));

        service.markUsed(track.getId());

        assertThat(track.getLastUsedAt()).isNotNull();
    }

    @Test
    void suggestionsRankPartyFavoriteAndConfirmedGenresFirst() {
        var favorite = track("Favorite", Set.of("Punk"), Set.of("party-1"));
        var other = track("Other", Set.of("Techno"), Set.of());
        when(tracks.findAll()).thenReturn(List.of(other, favorite));
        when(calendars.findById("entry-1")).thenReturn(Optional.of(event("party-1", "event")));
        EditorialPostDraft draft = mock(EditorialPostDraft.class);
        when(draft.getGenres()).thenReturn(Set.of("Punk"));
        when(drafts.findByCalendarEntryId("entry-1")).thenReturn(Optional.of(draft));

        List<MusicTrackResponse> suggestions = service.getSuggestions("entry-1");

        assertThat(suggestions).extracting(suggestion -> suggestion.id())
                .containsExactly(favorite.getId(), other.getId());
        assertThat(suggestions.getFirst().score()).isEqualTo(125);
        assertThat(suggestions.getFirst().matchReasons())
                .containsExactly("Partyreihen-Favorit", "Bestätigte Event-Genres");
        assertThat(suggestions.getLast().score()).isZero();
    }

    @Test
    void blankCalendarEntryIdSkipsContextLookupAndScoring() {
        var favorite = track("Favorite", Set.of("Punk"), Set.of("party-1"));
        when(tracks.findAll()).thenReturn(List.of(favorite));

        List<MusicTrackResponse> suggestions = service.getSuggestions("  ");

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.getFirst().score()).isZero();
        assertThat(suggestions.getFirst().matchReasons()).isEmpty();
        verifyNoInteractions(calendars, drafts);
    }

    @Test
    void suggestionsRejectMissingAndNonEventCalendarEntries() {
        when(calendars.findById("missing")).thenReturn(Optional.empty());
        assertStatus(() -> service.getSuggestions("missing"), HttpStatus.NOT_FOUND.value());

        when(calendars.findById("block")).thenReturn(Optional.of(event("party-1", "block")));
        assertThatThrownBy(() -> service.getSuggestions("block"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
                    assertThat(error).hasMessageContaining("Veranstaltungen");
                });
    }

    @Test
    void recentlyUsedTracksLoseScoreWithoutInventingAnAudioConfirmation() {
        var used = track("Used", Set.of("Punk"), Set.of());
        used.markUsed(OffsetDateTime.now(ZoneOffset.UTC));
        when(tracks.findAll()).thenReturn(List.of(used));

        MusicTrackResponse suggestion = service.getSuggestions(null).getFirst();

        assertThat(suggestion.score()).isEqualTo(-30);
        assertThat(suggestion.matchReasons()).containsExactly("Kürzlich redaktionell verwendet");
    }
}
