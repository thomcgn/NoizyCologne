package de.noizycologne.backend.genre;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Profile({"local", "staging"})
@RestController
@RequestMapping("/api/v1/genres")
public class GenreController {
    private final GenreService service;

    public GenreController(GenreService service) { this.service = service; }

    @GetMapping
    public List<GenreResponse> list() { return service.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GenreResponse create(@Valid @RequestBody GenreRequest request) { return service.create(request); }

    @PutMapping("/{id}")
    public GenreResponse update(@PathVariable String id, @Valid @RequestBody GenreRequest request) { return service.update(id, request); }

    @PostMapping("/{genreId}/aliases")
    @ResponseStatus(HttpStatus.CREATED)
    public GenreAliasResponse addAlias(@PathVariable String genreId, @Valid @RequestBody GenreAliasRequest request) { return service.addAlias(genreId, request); }

    @PutMapping("/{genreId}/aliases/{aliasId}")
    public GenreAliasResponse updateAlias(@PathVariable String genreId, @PathVariable String aliasId,
                                          @Valid @RequestBody GenreAliasRequest request) { return service.updateAlias(genreId, aliasId, request); }

    @DeleteMapping("/{genreId}/aliases/{aliasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAlias(@PathVariable String genreId, @PathVariable String aliasId) { service.deleteAlias(genreId, aliasId); }
}
