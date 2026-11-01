package de.noizycologne.backend.media;

import de.noizycologne.backend.calendar.CalendarEntry;
import de.noizycologne.backend.calendar.CalendarEntryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class FlyerGenreAnalysisService {

    private final CalendarEntryRepository calendarEntryRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final FlyerGenreAnalyzer analyzer;
    private final Path mediaRoot;

    public FlyerGenreAnalysisService(CalendarEntryRepository calendarEntryRepository,
                                     MediaAssetRepository mediaAssetRepository,
                                     FlyerGenreAnalyzer analyzer,
                                     @Value("${noizy.media.root:./uploads}") String mediaRoot) {
        this.calendarEntryRepository = calendarEntryRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.analyzer = analyzer;
        this.mediaRoot = Path.of(mediaRoot).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public FlyerGenreAnalysisResponse analyze(String entryId) {
        CalendarEntry entry = calendarEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kalendereintrag nicht gefunden"));
        if (!"event".equals(entry.getEntryType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nur Veranstaltungen können analysiert werden");
        }
        if (entry.getEventFlyerMediaId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Für diese Veranstaltung ist kein Flyer hinterlegt");
        }
        MediaAsset asset = mediaAssetRepository.findById(entry.getEventFlyerMediaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Flyer-Metadaten nicht gefunden"));
        Path imagePath = mediaRoot.resolve(asset.getStoragePath()).normalize();
        if (!mediaRoot.equals(imagePath.getParent()) || !Files.isRegularFile(imagePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flyerdatei nicht gefunden");
        }
        return analyzer.analyze(asset, imagePath);
    }
}
