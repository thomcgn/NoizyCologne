package de.noizycologne.backend.discovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/discovery/special-sources")
public class SpecialSourceController {
    private final SpecialSourceService sources;
    public SpecialSourceController(SpecialSourceService sources) { this.sources=sources; }
    public record Create(@NotBlank @Size(max=200) String name,@NotBlank @Size(max=2048) String url,
                         @Size(max=300) String itemSelector,@Size(max=300) String titleSelector,@Size(max=300) String dateSelector,@org.jspecify.annotations.Nullable Boolean approved) { }
    @GetMapping public List<Map<String,Object>> list() { return sources.list(); }
    @PostMapping public Map<String,String> add(@Valid @RequestBody Create request) {
        return Map.of("id",sources.add(request.name(),request.url(),Objects.toString(request.itemSelector(),""),Objects.toString(request.titleSelector(),""),Objects.toString(request.dateSelector(),""),Boolean.TRUE.equals(request.approved())));
    }
    @PutMapping("/{id}") public void update(@PathVariable String id,@Valid @RequestBody Create request) {
        sources.update(id,request.name(),request.url(),Objects.toString(request.itemSelector(),""),Objects.toString(request.titleSelector(),""),Objects.toString(request.dateSelector(),""),request.approved());
    }
    @DeleteMapping("/{id}") public void delete(@PathVariable String id) { sources.delete(id); }
}
