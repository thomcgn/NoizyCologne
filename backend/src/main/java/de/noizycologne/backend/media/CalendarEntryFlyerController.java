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
@RequestMapping("/api/v1/calendar-entries/{entryId}/flyer")
public class CalendarEntryFlyerController {

    private final CalendarEntryFlyerService flyerService;
    private final FlyerGenreAnalysisService genreAnalysisService;

    public CalendarEntryFlyerController(CalendarEntryFlyerService flyerService,
                                        FlyerGenreAnalysisService genreAnalysisService) {
        this.flyerService = flyerService;
        this.genreAnalysisService = genreAnalysisService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PartyFlyerResponse upload(@PathVariable String entryId,
                                     @RequestPart("file") MultipartFile file) {
        return flyerService.upload(entryId, file);
    }

    @PostMapping("/analysis")
    public FlyerGenreAnalysisResponse analyzeGenres(@PathVariable String entryId) {
        return genreAnalysisService.analyze(entryId);
    }

    @GetMapping
    public ResponseEntity<Resource> download(@PathVariable String entryId) {
        CalendarEntryFlyerService.DownloadedFlyer flyer = flyerService.download(entryId);
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
    public void delete(@PathVariable String entryId) {
        flyerService.delete(entryId);
    }
}
