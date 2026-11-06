package de.noizycologne.backend.cancellation;

import de.noizycologne.backend.discovery.DiscoveryStore;
import de.noizycologne.backend.media.MediaAsset;
import de.noizycologne.backend.media.MediaAssetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.List;

/** The sole confirmed event-cancellation workflow, shared by manual, official and DM decisions. */
@Service
public class CancellationService {
    private final DiscoveryStore store;
    private final MediaAssetRepository assets;
    private final Path mediaRoot;
    public CancellationService(DiscoveryStore store,MediaAssetRepository assets,@Value("${noizy.media.root:./uploads}") String root) {
        this.store=store;this.assets=assets;mediaRoot=Path.of(root).toAbsolutePath().normalize();
    }
    @Transactional
    public String confirm(String entry,String change,String trigger,String reason,String replacement) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        if (trigger==null || !Set.of("MANUAL","OFFICIAL_SOURCE","DM_CONFIRMED").contains(trigger)) throw new IllegalArgumentException("Bestätigter Absageauslöser erforderlich");
        if (reason==null || !Set.of("UNSPECIFIED","PERSONALBEDINGTER_AUSFALL","ORGANISATORISCH","REPLACEMENT").contains(reason)) throw new IllegalArgumentException("Unbekannter allgemeiner Absagegrund; keine Gesundheitsdetails speichern");
        var rows=store.jdbc().queryForList("SELECT e.*,v.name AS venue_name,p.name AS party_name FROM calendar_entries e JOIN venues v ON v.id=e.location_id LEFT JOIN party_series p ON p.id=e.party_id WHERE e.id=? AND e.entry_type='event'",entry);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Veranstaltung fehlt");
        var existing=store.jdbc().queryForList("SELECT id,replacement_candidate_id FROM event_cancellations WHERE calendar_entry_id=?",entry);
        String source="";
        if (change!=null) {
            var changes=store.jdbc().queryForList("SELECT * FROM event_changes WHERE id=? AND calendar_entry_id=?",change,entry);
            if (changes.isEmpty()) throw new IllegalArgumentException("Änderung gehört nicht zur Veranstaltung");
            var found=changes.getFirst();source=(String)found.get("source_url");
            if (trigger.equals("OFFICIAL_SOURCE") && !"CANCELLATION_DETECTED".equals(found.get("kind"))) throw new IllegalArgumentException("Keine ausdrückliche offizielle Veranstaltungsabsage");
            if (trigger.equals("DM_CONFIRMED") && (!"ANSWERED".equals(found.get("inquiry_status")) || Objects.toString(found.get("answer"),"").isBlank())) throw new IllegalArgumentException("Bestätigte DM-Antwort fehlt");
        } else if (!trigger.equals("MANUAL")) throw new IllegalArgumentException("Quellenbeleg erforderlich");
        if(reason.equals("REPLACEMENT") && replacement==null) throw new IllegalArgumentException("Bestätigten Ersatzkandidaten auswählen");
        if (replacement!=null && !Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM discovery_candidates WHERE id=? AND venue_id=? AND event_date=? AND calendar_entry_id IS DISTINCT FROM ? AND status NOT IN ('IGNORED','REJECTED'))",Boolean.class,replacement,rows.getFirst().get("location_id"),rows.getFirst().get("event_date"),entry))) throw new IllegalArgumentException("Ersatzkandidat muss zur selben Location und zum selben Tag gehören");
        if(!existing.isEmpty()) {
            var previous=existing.getFirst();String id=(String)previous.get("id");
            if(replacement!=null && !replacement.equals(previous.get("replacement_candidate_id"))) {
                if(previous.get("replacement_candidate_id")!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Bereits ein anderer Ersatz bestätigt");
                store.jdbc().update("UPDATE event_cancellations SET replacement_candidate_id=? WHERE id=?",replacement,id);
                store.jdbc().update("INSERT INTO cancellation_history(cancellation_id,action,note) VALUES (?,'REPLACEMENT_LINKED','Eigenständiger Ersatzkandidat nachträglich bestätigt')",id);
            }
            return id;
        }
        var event=rows.getFirst();String id=UUID.randomUUID().toString();
        String caption="Leider kann die Veranstaltung „"+event.get("party_name")+"“ am "+event.get("event_date")+" in der "+event.get("venue_name")+" nicht wie geplant stattfinden. Die Party wurde abgesagt. Informationen zu möglichen Ersatzterminen folgen.";
        store.jdbc().update("INSERT INTO event_cancellations(id,calendar_entry_id,change_id,trigger_type,reason_code,source_url,announcement_caption,replacement_candidate_id) VALUES (?,?,?,?,?,?,?,?)",id,entry,change,trigger,reason,source,caption,replacement);
        store.jdbc().update("UPDATE calendar_entries SET event_status='CANCELLED' WHERE id=?",entry);
        store.jdbc().update("UPDATE editorial_post_drafts SET approval_needs_review=true WHERE calendar_entry_id=?",entry);
        store.jdbc().update("INSERT INTO cancellation_history(cancellation_id,action,note) VALUES (?,'CONFIRMED',?)",id,trigger+" / "+reason);
        if(change!=null) store.jdbc().update("INSERT INTO event_change_history(change_id,action,note) VALUES (?,'CANCELLATION_CONFIRMED',?)",change,trigger+" / "+reason);
        if(change!=null) store.jdbc().update("UPDATE event_changes SET state='CONFIRMED',blocks_publication=true,updated_at=now(),revision=revision+1 WHERE id=?",change);
        return id;
    }
    public List<Map<String,Object>> list() {
        return store.jdbc().queryForList("SELECT c.*,v.name AS venue_name,p.name AS party_name,e.event_date FROM event_cancellations c JOIN calendar_entries e ON e.id=c.calendar_entry_id JOIN venues v ON v.id=e.location_id LEFT JOIN party_series p ON p.id=e.party_id ORDER BY c.confirmed_at DESC LIMIT 500").stream().map(DiscoveryStore::forApi).toList();
    }
    public List<Map<String,Object>> history(String id) {
        return store.jdbc().queryForList("SELECT * FROM cancellation_history WHERE cancellation_id=? ORDER BY id",id).stream().map(DiscoveryStore::forApi).toList();
    }
    public Map<String,Object> get(String id) {
        var rows=store.jdbc().queryForList("SELECT * FROM event_cancellations WHERE id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Cancellation fehlt");
        return rows.getFirst();
    }
    @Transactional(rollbackFor=IOException.class)
    public String prepareGraphic(String id) throws IOException {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var cancellation=get(id);
        if(cancellation.get("media_asset_id") instanceof String existing) return existing;
        var sources=store.jdbc().queryForList("SELECT COALESCE(e.event_flyer_media_id,p.default_flyer_media_id) AS media FROM calendar_entries e LEFT JOIN party_series p ON p.id=e.party_id WHERE e.id=?",cancellation.get("calendar_entry_id"));
        BufferedImage background=null;
        if (!sources.isEmpty() && sources.getFirst().get("media") instanceof String media) {
            var asset=assets.findById(media).orElseThrow(() -> new IllegalArgumentException("Originalflyer fehlt"));
            Path path=mediaRoot.resolve(asset.getStoragePath()).normalize();
            if(!path.getParent().equals(mediaRoot) || Files.size(path)>10*1024*1024) throw new IllegalArgumentException("Ungültiger Originalflyer");
            background=ImageIO.read(path.toFile());
            if(background==null) throw new IOException("Originalflyer nicht lesbar");
        }
        BufferedImage image=new BufferedImage(1080,1350,BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);graphics.fillRect(0,0,1080,1350);
            if(background!=null) graphics.drawImage(background,0,0,1080,1350,null);
            graphics.setColor(new Color(0,0,0,210));graphics.fillRect(0,480,1080,370);
            graphics.setColor(Color.WHITE);graphics.setFont(new Font(Font.SANS_SERIF,Font.BOLD,112));
            graphics.drawString("CANCELLED",(1080-graphics.getFontMetrics().stringWidth("CANCELLED"))/2,630);
            graphics.setFont(new Font(Font.SANS_SERIF,Font.BOLD,76));
            graphics.drawString("ABGESAGT",(1080-graphics.getFontMetrics().stringWidth("ABGESAGT"))/2,760);
        } finally { graphics.dispose(); }
        Files.createDirectories(mediaRoot);String media=UUID.randomUUID().toString();Path target=mediaRoot.resolve(media+".png");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) try { Files.deleteIfExists(target); } catch(IOException exception) { org.slf4j.LoggerFactory.getLogger(CancellationService.class).warn("Rollback-Mediendatei konnte nicht entfernt werden: {}",target); } }
        });
        if(!ImageIO.write(image,"png",target.toFile())) throw new IOException("PNG-Encoder fehlt");
        String checksum;
        try { checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target))); }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        assets.saveAndFlush(new MediaAsset(media,target.getFileName().toString(),"cancelled.png","image/png",Files.size(target),checksum));
        store.jdbc().update("UPDATE event_cancellations SET media_asset_id=? WHERE id=?",media,id);
        store.jdbc().update("INSERT INTO cancellation_history(cancellation_id,action,note) VALUES (?,'GRAPHIC_PREPARED','Neues MediaAsset; Original bleibt erhalten')",id);
        return media;
    }
    public FileSystemResource graphic(String id) {
        var data=get(id);
        if(!(data.get("media_asset_id") instanceof String media)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Absagegrafik noch nicht vorbereitet");
        var asset=assets.findById(media).orElseThrow();Path path=mediaRoot.resolve(asset.getStoragePath()).normalize();
        if(!path.getParent().equals(mediaRoot) || !Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Absagegrafik fehlt");
        return new FileSystemResource(path);
    }
    @Transactional
    public void updateAnnouncement(String id,String caption,String status,long revision) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");var data=get(id);
        if(!Set.of("DRAFT","READY_FOR_REVIEW","APPROVED").contains(status)) throw new IllegalArgumentException("Ungültiger Absageentwurfsstatus");
        if(status.equals("APPROVED") && (caption.isBlank() || data.get("media_asset_id")==null)) throw new IllegalArgumentException("Absagefreigabe benötigt Text und eigene Grafik");
        if(caption.matches("(?is).*(krank|diagnos|gesundheit|illness|medical).*")) throw new IllegalArgumentException("Öffentlichen Absagetext neutral halten; keine Gesundheitsdetails");
        int changed=store.jdbc().update("UPDATE event_cancellations SET announcement_caption=?,announcement_status=?,revision=revision+1 WHERE id=? AND revision=?",caption.trim(),status,id,revision);
        if(changed==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Absageentwurf inzwischen geändert; neu laden");
        store.jdbc().update("INSERT INTO cancellation_history(cancellation_id,action,note) VALUES (?,'ANNOUNCEMENT_UPDATED',?)",id,status+"; keine Veröffentlichung ausgelöst");
    }
}
