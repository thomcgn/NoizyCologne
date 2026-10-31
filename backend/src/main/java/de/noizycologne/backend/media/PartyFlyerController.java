package de.noizycologne.backend.media;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;

@Profile({"local", "staging"})
@RestController
@RequestMapping("/api/v1/parties/{partyId}/default-flyer")
public class PartyFlyerController {

    private final PartyFlyerService partyFlyerService;

    public PartyFlyerController(PartyFlyerService partyFlyerService) {
        this.partyFlyerService = partyFlyerService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PartyFlyerResponse upload(@PathVariable String partyId,
                                     @RequestPart("file") MultipartFile file) {
        return partyFlyerService.upload(partyId, file);
    }

    @GetMapping
    public ResponseEntity<Resource> download(@PathVariable String partyId) {
        PartyFlyerService.DownloadedFlyer flyer = partyFlyerService.download(partyId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(flyer.asset().getMediaType()));
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(flyer.asset().getOriginalFilename())
                .build());
        headers.setCacheControl("private, no-store");
        return ResponseEntity.ok().headers(headers).body(flyer.resource());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String partyId) {
        partyFlyerService.delete(partyId);
    }
}
