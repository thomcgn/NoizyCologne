package de.noizycologne.backend.discovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/discovery/geography")
public class DiscoveryGeographyController {
    private final DiscoveryGeographyService geography;
    private final EventCandidateProcessor processor;
    private final DiscoveryStore store;
    public DiscoveryGeographyController(DiscoveryGeographyService geography,EventCandidateProcessor processor,DiscoveryStore store){this.geography=geography;this.processor=processor;this.store=store;}
    @GetMapping public Map<String,Object> list(){return geography.list();}
    public record Confirmation(@Size(max=100) String venueId,@Size(max=200) String venueName,@NotBlank @Size(max=200) String city,
                               @NotBlank @Size(max=300) String street,@NotBlank @Size(max=2048) String proofUrl,boolean confirmed) { }
    @Transactional
    @PostMapping("/{id}/confirm") public void confirm(@PathVariable String id,@Valid @RequestBody Confirmation request){
        var finding=geography.confirm(id,request.venueId(),request.venueName(),request.city(),request.street(),request.proofUrl(),request.confirmed());
        String operation=UUID.randomUUID().toString();
        store.jdbc().update("INSERT INTO discovery_operations(id,kind,request_hash,matcher_version,status,finished_at,report) VALUES (?,'GEO_CONFIRM',?,?,'COMPLETED',now(),'Kölner Veranstaltungsort redaktionell bestätigt')",operation,EventNormalizer.hash(finding.id()+request.proofUrl()),EventMatcher.VERSION);
        processor.processSpecial(finding.sourceId(),finding.event(),operation);
    }
}
