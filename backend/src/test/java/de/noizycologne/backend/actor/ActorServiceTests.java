package de.noizycologne.backend.actor;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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

class ActorServiceTests {

    private final ActorRepository repository = mock(ActorRepository.class);
    private final ActorService service = new ActorService(repository);

    private static Actor person(String name) {
        var actor = new Actor(name, "person", "", Set.of("dj"), Set.of());
        actor.assignId();
        return actor;
    }

    private static Actor collective(String name) {
        var actor = new Actor(name, "collective", "", Set.of(), Set.of());
        actor.assignId();
        return actor;
    }

    /** Mockito's matchers are legacy APIs; keep their unannotated values at this boundary. */
    private static Actor anyActor() {
        return org.mockito.ArgumentMatchers.any();
    }

    private void saveAssignsId() {
        when(repository.save(anyActor())).thenAnswer(call -> {
            var saved = Objects.requireNonNull(call.getArgument(0, Actor.class));
            saved.assignId();
            return saved;
        });
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void createActorTrimsNameAndPersistsValidRoles() {
        when(repository.findByNameIgnoreCase("NC Test DJ")).thenReturn(Optional.empty());
        saveAssignsId();

        ActorResponse response = service.createActor(new ActorRequest(
                " NC Test DJ ", "person", Set.of("dj"), "nc_dj", Set.of()));

        assertThat(response.name()).isEqualTo("NC Test DJ");
        assertThat(response.kind()).isEqualTo("person");
        assertThat(response.roles()).containsExactly("dj");
        assertThat(response.id()).matches("[0-9a-f-]{36}");
        verify(repository).findByNameIgnoreCase("NC Test DJ");
        verify(repository).save(anyActor());
    }

    @Test
    void getActorsMapsAllPersistedActors() {
        var person = person("NC Person");
        var collective = collective("NC Collective");
        when(repository.findAll()).thenReturn(List.of(person, collective));

        assertThat(service.getActors()).extracting(response -> response.name())
                .containsExactly("NC Person", "NC Collective");
    }

    @Test
    void duplicateNameWasAlreadyTakenIsRejectedBeforeSaving() {
        when(repository.findByNameIgnoreCase("NC Duplicate")).thenReturn(Optional.of(person("NC Duplicate")));

        assertStatus(() -> service.createActor(new ActorRequest(
                "NC Duplicate", "person", Set.of("dj"), "", Set.of())), HttpStatus.CONFLICT.value());
        verify(repository, never()).save(anyActor());
    }

    @Test
    void updateAcceptsOwnNameAndChangesRoles() {
        var actor = person("NC Self");
        when(repository.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(repository.findByNameIgnoreCase("NC Self")).thenReturn(Optional.of(actor));
        saveAssignsId();

        ActorResponse updated = service.updateActor(actor.getId(), new ActorRequest(
                "NC Self", "person", Set.of("dj", "organizer"), "nc_self_updated", Set.of()));
        assertThat(updated.roles()).containsExactly("dj", "organizer");
        assertThat(updated.instagram()).isEqualTo("nc_self_updated");
        verify(repository).save(actor);
    }

    @Test
    void updateRejectsNameAlreadyUsedByAnotherActor() {
        var actor = person("NC Self");
        var other = person("NC Other");
        when(repository.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(repository.findByNameIgnoreCase("NC Other")).thenReturn(Optional.of(other));

        assertStatus(() -> service.updateActor(actor.getId(), new ActorRequest(
                "NC Other", "person", Set.of("dj"), "", Set.of())), HttpStatus.CONFLICT.value());
        verify(repository, never()).save(anyActor());
    }

    @Test
    void unknownKindAndRoleAreBadRequestsWithoutTouchingRepository() {
        assertThatThrownBy(() -> service.createActor(new ActorRequest(
                "NC Invalid", "band", Set.of(), "", Set.of())))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
                    assertThat(error).hasMessageContaining("kind");
                });
        assertThatThrownBy(() -> service.createActor(new ActorRequest(
                "NC Invalid", "person", Set.of("mc"), "", Set.of())))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
                    assertThat(error).hasMessageContaining("roles");
                });
        verifyNoInteractions(repository);
    }

    @Test
    void membershipsOnlyWorkForPersonsReferencingExistingCollectives() {
        when(repository.findByNameIgnoreCase("NC Person")).thenReturn(Optional.empty());
        when(repository.findAllById(Objects.requireNonNull(Set.of("missing")))).thenReturn(List.of());

        assertThatThrownBy(() -> service.createActor(new ActorRequest(
                "NC Person", "person", Set.of("dj"), "", Set.of("missing"))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
                    assertThat(error).hasMessageContaining("Kollektiv");
                });

        assertThatThrownBy(() -> service.createActor(new ActorRequest(
                "NC Collective", "collective", Set.of(), "", Set.of("any-collective"))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
                    assertThat(error).hasMessageContaining("Nur Personen");
                });

        var reference = collective("NC Reference");
        when(repository.findByNameIgnoreCase("NC Member")).thenReturn(Optional.empty());
        when(repository.findAllById(Objects.requireNonNull(Set.of(reference.getId())))).thenReturn(List.of(reference));
        saveAssignsId();
        ActorResponse member = service.createActor(new ActorRequest(
                "NC Member", "person", Set.of("dj"), "", Set.of(reference.getId())));
        assertThat(member.collectiveIds()).containsExactly(reference.getId());
    }

    @Test
    void updateRejectsUnknownIdsAndCollectivesWithExistingMembers() {
        when(repository.findById("missing")).thenReturn(Optional.empty());
        assertStatus(() -> service.updateActor("missing", new ActorRequest(
                "NC Missing", "person", Set.of("dj"), "", Set.of())), HttpStatus.NOT_FOUND.value());

        var collective = collective("NC Collective With Members");
        when(repository.findById(collective.getId())).thenReturn(Optional.of(collective));
        when(repository.findByNameIgnoreCase("NC Collective With Members")).thenReturn(Optional.empty());
        when(repository.countMembershipsAsCollective(collective.getId())).thenReturn(2L);

        assertThatThrownBy(() -> service.updateActor(collective.getId(), new ActorRequest(
                "NC Collective With Members", "person", Set.of(), "", Set.of())))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
                    assertThat(error).hasMessageContaining("Kollektiv mit bestehenden Mitgliedern");
                });
        verify(repository, never()).save(anyActor());
    }

    @Test
    void deleteRejectsMissingAndReferencedActorsButDeletesUnusedOnes() {
        assertStatus(() -> service.deleteActor("missing"), HttpStatus.NOT_FOUND.value());
        verify(repository, never()).delete(anyActor());

        var referenced = person("NC Referenced");
        when(repository.findById(referenced.getId())).thenReturn(Optional.of(referenced));
        when(repository.countPartyDjReferences(referenced.getId())).thenReturn(1L);
        assertStatus(() -> service.deleteActor(referenced.getId()), HttpStatus.CONFLICT.value());
        verify(repository, never()).delete(anyActor());

        var unused = person("NC Unused");
        when(repository.findById(unused.getId())).thenReturn(Optional.of(unused));
        service.deleteActor(unused.getId());
        verify(repository).delete(unused);
    }
}
