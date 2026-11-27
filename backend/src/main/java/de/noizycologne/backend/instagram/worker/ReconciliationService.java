package de.noizycologne.backend.instagram.worker;

import de.noizycologne.backend.instagram.InstagramAccount;
import de.noizycologne.backend.instagram.InstagramGateway;
import de.noizycologne.backend.instagram.RemotePublication;
import de.noizycologne.backend.instagram.store.PublishJob;
import de.noizycologne.backend.instagram.store.PublishJobStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Klärt {@code UNKNOWN_REMOTE_RESULT} ausschließlich mit lesenden Gateway-Methoden.
 * Es wird nie ein Container erzeugt oder {@code media_publish} aufgerufen; ein
 * fehlender Befund bleibt ausdrücklich unbekannt.
 */
@Service
public class ReconciliationService {
    public enum Outcome { CONFIRMED, UNRESOLVED }

    private final PublishJobStore store;
    private final InstagramGateway gateway;
    private final int recentLimit;

    public ReconciliationService(PublishJobStore store, InstagramGateway gateway,
                                 @Value("${noizy.instagram.worker.reconciliation-limit:25}") int recentLimit) {
        this.store = Objects.requireNonNull(store, "store");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.recentLimit = Math.max(1, Math.min(recentLimit, 100));
    }

    public Outcome reconcile(PublishJob job) {
        Objects.requireNonNull(job, "job");
        InstagramAccount account = new InstagramAccount(job.accountId());
        @Nullable String mediaId = job.mediaId();
        if (mediaId != null && !mediaId.isBlank()) {
            Optional<@NonNull RemotePublication> found = gateway.fetchPublication(account, mediaId);
            if (found.isPresent()) {
                store.confirmReconciled(job, found.get(), "Per fetchPublication bestätigt");
                return Outcome.CONFIRMED;
            }
        }
        for (RemotePublication publication : gateway.recentMedia(account, recentLimit)) {
            if (job.caption().equals(publication.caption())) {
                store.confirmReconciled(job, publication, "Per recentMedia bestätigt");
                return Outcome.CONFIRMED;
            }
        }
        store.keepUnknown(job.id(), "Reconciliation ohne Treffer; Remote-Zustand weiterhin unbekannt");
        return Outcome.UNRESOLVED;
    }

    public void reconcileUnknownJobs() {
        List<PublishJob> jobs = store.findJobsNeedingReconciliation(recentLimit);
        for (PublishJob job : jobs) {
            reconcile(job);
        }
    }
}
