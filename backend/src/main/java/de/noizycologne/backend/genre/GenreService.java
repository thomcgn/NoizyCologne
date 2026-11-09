package de.noizycologne.backend.genre;

import org.jspecify.annotations.NonNull;
import de.noizycologne.backend.media.GenreLexicon;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GenreService {
    private final GenreRepository genreRepository;
    private final GenreAliasRepository aliasRepository;
    private final GenreLexicon genreLexicon;
    private final JdbcTemplate jdbc;

    public GenreService(GenreRepository genreRepository, GenreAliasRepository aliasRepository, GenreLexicon genreLexicon, JdbcTemplate jdbc) {
        this.genreRepository = genreRepository;
        this.aliasRepository = aliasRepository;
        this.genreLexicon = genreLexicon;
        this.jdbc = jdbc;
    }

    @Transactional
    public List<GenreResponse> list() {
        return responses(genreRepository.findAllByOrderByCanonicalNameAsc());
    }

    @Transactional
    public GenreResponse create(GenreRequest request) {
        lockCatalogue();
        String canonical = clean(request.canonicalName());
        String display = clean(request.displayName());
        ensureCanonicalAvailable(canonical, null);
        Genre genre = genreRepository.save(new Genre(canonical, display, request.activeOrDefault()));
        refreshLexiconAfterCommit();
        return response(genre);
    }

    @Transactional
    public GenreResponse update(String id, GenreRequest request) {
        lockCatalogue();
        Genre genre = findGenre(id);
        String canonical = clean(request.canonicalName());
        ensureCanonicalAvailable(canonical, id);
        genre.update(canonical, clean(request.displayName()), request.activeOrDefault());
        Genre saved = genreRepository.save(genre);
        refreshLexiconAfterCommit();
        return response(saved);
    }

    @Transactional
    public GenreAliasResponse addAlias(String genreId, GenreAliasRequest request) {
        lockCatalogue();
        findGenre(genreId);
        String value = clean(request.value());
        ensureAliasAvailable(value, null);
        GenreAlias alias = aliasRepository.save(new GenreAlias(genreId, value));
        refreshLexiconAfterCommit();
        return GenreAliasResponse.from(alias);
    }

    @Transactional
    public GenreAliasResponse updateAlias(String genreId, String aliasId, GenreAliasRequest request) {
        lockCatalogue();
        findGenre(genreId);
        GenreAlias alias = aliasRepository.findById(aliasId)
                .filter(item -> item.getGenreId().equals(genreId))
                .orElseThrow(() -> notFound("Alias nicht gefunden"));
        String value = clean(request.value());
        ensureAliasAvailable(value, aliasId);
        alias.update(value);
        GenreAlias saved = aliasRepository.save(alias);
        refreshLexiconAfterCommit();
        return GenreAliasResponse.from(saved);
    }

    @Transactional
    public void deleteAlias(String genreId, String aliasId) {
        lockCatalogue();
        findGenre(genreId);
        GenreAlias alias = aliasRepository.findById(aliasId)
                .filter(item -> item.getGenreId().equals(genreId))
                .orElseThrow(() -> notFound("Alias nicht gefunden"));
        aliasRepository.delete(alias);
        refreshLexiconAfterCommit();
    }

    private Genre findGenre(String id) {
        return genreRepository.findById(id).orElseThrow(() -> notFound("Genre nicht gefunden"));
    }

    private GenreResponse response(Genre genre) {
        return GenreResponse.from(genre, aliasRepository.findAllByGenreIdIn(List.of(genre.getId())).stream()
                .map(alias -> GenreAliasResponse.from(alias)).toList());
    }

    private List<GenreResponse> responses(List<@NonNull Genre> genres) {
        Map<String, List<GenreAliasResponse>> aliases = new LinkedHashMap<>();
        aliasRepository.findAllByGenreIdIn(genres.stream().map(genre -> genre.getId()).toList()).forEach(alias ->
                aliases.computeIfAbsent(alias.getGenreId(), ignored -> new java.util.ArrayList<>()).add(GenreAliasResponse.from(alias)));
        return genres.stream().map(genre -> GenreResponse.from(genre, aliases.getOrDefault(genre.getId(), List.of()))).toList();
    }

    private void ensureCanonicalAvailable(String value, String currentId) {
        boolean duplicate = genreRepository.findAll().stream().anyMatch(item ->
                !item.getId().equals(currentId) && normalize(item.getCanonicalName()).equals(normalize(value)));
        if (duplicate) throw conflict("Ein Genre mit diesem kanonischen Namen existiert bereits");
    }

    private void ensureAliasAvailable(String value, String currentId) {
        boolean duplicate = aliasRepository.findAllByOrderByValueAsc().stream().anyMatch(item ->
                !item.getId().equals(currentId) && normalize(item.getValue()).equals(normalize(value)));
        if (duplicate) throw conflict("Dieser Alias ist bereits einem Genre zugeordnet");
    }

    private String clean(String value) { return value == null ? "" : value.trim(); }

    private String normalize(String value) { return GenreNormalizer.normalize(value); }

    void lockCatalogue() {
        jdbc.execute("SET LOCAL lock_timeout = '1500ms'");
        jdbc.execute("LOCK TABLE genres, genre_aliases IN SHARE ROW EXCLUSIVE MODE");
    }

    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    void refreshLexiconAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            genreLexicon.refresh();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { genreLexicon.refresh(); }
        });
    }
}
