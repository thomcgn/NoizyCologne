package de.noizycologne.backend.media;

import de.noizycologne.backend.party.PartySeries;
import de.noizycologne.backend.party.PartySeriesRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.UUID;

@Service
public class PartyFlyerService {

    private static final int MAX_PIXELS = 40_000_000;

    private final PartySeriesRepository partySeriesRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final Path mediaRoot;

    public PartyFlyerService(PartySeriesRepository partySeriesRepository,
                             MediaAssetRepository mediaAssetRepository,
                             @Value("${noizy.media.root:./uploads}") String mediaRoot) {
        this.partySeriesRepository = partySeriesRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.mediaRoot = Path.of(mediaRoot).toAbsolutePath().normalize();
    }

    @Transactional
    public PartyFlyerResponse upload(String partyId, MultipartFile file) {
        PartySeries party = findParty(partyId);
        ValidatedImage image = validateImage(file);
        String mediaId = UUID.randomUUID().toString();
        String extension = "image/png".equals(image.mediaType()) ? ".png" : ".jpg";
        String storageName = mediaId + extension;
        Path target = mediaRoot.resolve(storageName).normalize();
        if (!target.getParent().equals(mediaRoot)) {
            throw badRequest("Ungültiger Medienpfad");
        }

        try {
            Files.createDirectories(mediaRoot);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            MediaAsset asset = mediaAssetRepository.save(new MediaAsset(
                    mediaId,
                    storageName,
                    safeFilename(file.getOriginalFilename()),
                    image.mediaType(),
                    image.fileSize(),
                    sha256(target)
            ));
            party.setDefaultFlyerMediaId(asset.getId());
            partySeriesRepository.save(party);
            return toResponse(partyId, asset);
        } catch (IOException exception) {
            deleteQuietly(target);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Der Flyer konnte nicht gespeichert werden", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(target);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public PartyFlyerResponse getMetadata(String partyId) {
        MediaAsset asset = findAsset(findParty(partyId));
        return toResponse(partyId, asset);
    }

    @Transactional(readOnly = true)
    public DownloadedFlyer download(String partyId) {
        MediaAsset asset = findAsset(findParty(partyId));
        Path path = mediaRoot.resolve(asset.getStoragePath()).normalize();
        if (!path.getParent().equals(mediaRoot) || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flyerdatei nicht gefunden");
        }
        try {
            Resource resource = new UrlResource(java.util.Objects.requireNonNull(path.toUri()));
            return new DownloadedFlyer(asset, resource);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flyerdatei nicht gefunden", exception);
        }
    }

    @Transactional
    public void delete(String partyId) {
        PartySeries party = findParty(partyId);
        MediaAsset asset = findAsset(party);
        party.setDefaultFlyerMediaId(null);
        partySeriesRepository.save(party);
        mediaAssetRepository.delete(asset);
        // Die Datei bleibt bis zu einem kontrollierten Storage-Cleanup erhalten.
        // Dadurch kann ein Datenbank-Rollback keinen gültigen Flyer verlieren.
    }

    private PartySeries findParty(String partyId) {
        return partySeriesRepository.findById(partyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Partyreihe nicht gefunden"));
    }

    private MediaAsset findAsset(PartySeries party) {
        if (party.getDefaultFlyerMediaId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Für diese Partyreihe ist kein Dauerflyer hinterlegt");
        }
        return mediaAssetRepository.findById(party.getDefaultFlyerMediaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Flyer-Metadaten nicht gefunden"));
    }

    private PartyFlyerResponse toResponse(String partyId, MediaAsset asset) {
        return new PartyFlyerResponse(asset.getId(), asset.getOriginalFilename(), asset.getMediaType(),
                asset.getFileSize(), "/api/v1/parties/" + partyId + "/default-flyer");
    }

    private ValidatedImage validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("Bitte eine Bilddatei auswählen");
        }
        if (file.getSize() > FlyerSizeLimit.MAX_BYTES) {
            throw badRequest(FlyerSizeLimit.tooLargeMessage());
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(file.getInputStream())) {
            if (input == null) throw badRequest("Die Datei ist kein gültiges Bild");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw badRequest("Nur JPEG- und PNG-Flyer werden unterstützt");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase();
                String mediaType = switch (format) {
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "png" -> "image/png";
                    default -> throw badRequest("Nur JPEG- und PNG-Flyer werden unterstützt");
                };
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw badRequest("Die Bildabmessungen sind nicht zulässig");
                }
                return new ValidatedImage(mediaType, file.getSize());
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw badRequest("Die Datei ist kein gültiger JPEG- oder PNG-Flyer");
        }
    }

    private String safeFilename(String filename) {
        String cleaned = StringUtils.cleanPath(filename == null ? "flyer" : filename);
        String base = Path.of(cleaned).getFileName().toString();
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    private String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", exception);
        }
    }

    private void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record DownloadedFlyer(MediaAsset asset, Resource resource) { }
    private record ValidatedImage(String mediaType, long fileSize) { }
}
