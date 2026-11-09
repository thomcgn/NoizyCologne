package de.noizycologne.backend.calendar;

import de.noizycologne.backend.discovery.DiscoveryStore;
import de.noizycologne.backend.party.PartySeries;
import de.noizycologne.backend.party.PartySeriesRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Explicitly configured recurrence plans; never an assertion that an event is confirmed. */
@Service
public class RecurrencePlanningService {
    private final DiscoveryStore store;
    private final CalendarEntryService calendar;
    private final PartySeriesRepository parties;
    private final Clock clock;
    @Autowired public RecurrencePlanningService(DiscoveryStore store,CalendarEntryService calendar,PartySeriesRepository parties) {
        this(store,calendar,parties,Clock.system(ZoneId.of("Europe/Berlin")));
    }
    public RecurrencePlanningService(DiscoveryStore store,CalendarEntryService calendar,PartySeriesRepository parties,Clock clock) {
        this.store=store;this.calendar=calendar;this.parties=parties;this.clock=clock.withZone(ZoneId.of("Europe/Berlin"));
    }
    public static List<@NonNull LocalDate> dates(String kind,int weekday,Integer occurrence,LocalDate start,LocalDate today) {
        if (!List.of("weekly","monthly_weekday").contains(kind) || weekday<0 || weekday>6) return List.of();
        if (kind.equals("monthly_weekday") && (occurrence==null || !Set.of(-1,1,2,3,4).contains(occurrence))) return List.of();
        DayOfWeek day=DayOfWeek.of(weekday==0 ? 7 : weekday);
        List<@NonNull LocalDate> dates=new ArrayList<>();
        for(LocalDate date=start.isAfter(today) ? start : today;date.isBefore(today.plusMonths(3));date=date.plusDays(1)) {
            if (date.getDayOfWeek()!=day) continue;
            if (kind.equals("weekly") || date.equals(date.with(TemporalAdjusters.dayOfWeekInMonth(Objects.requireNonNull(occurrence),day)))) dates.add(date);
        }
        return List.copyOf(dates);
    }
    public void lock() { store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)"); }
    @Scheduled(cron="${noizy.calendar.planning.cron:0 15 3 * * *}",zone="Europe/Berlin")
    @Transactional
    public void extendHorizon() {
        // Hold the mutation lease before reading parties; a reset must not invalidate this plan mid-run.
        if (!Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT pg_try_advisory_xact_lock_shared(73624604)", Boolean.class))
                || !Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT pending_reset IS NULL FROM maintenance_state WHERE singleton", Boolean.class)))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Wartungsarbeiten sind aktiv. Kalenderplanung wurde nicht gestartet.");
        lock();
        for(var party:parties.findAll()) if(party.getRecurrenceStartDate()!=null) reconcile(party);
    }
    @Transactional
    public void reconcile(PartySeries party) {
        lock();
        LocalDate today=LocalDate.now(clock);
        String location=party.getRecurrenceLocationId(),kind=party.getRecurrenceKind();
        var start=party.getRecurrenceStartDate();var weekday=party.getRecurrenceWeekday();var occurrence=party.getRecurrenceOccurrence();
        Set<LocalDate> desired=new LinkedHashSet<>();
        if(location!=null && kind!=null && start!=null && weekday!=null)
            desired.addAll(dates(kind,weekday,occurrence==null ? null : occurrence.intValue(),start,today));
        // Lock entry rows before reading protection flags, including concurrent flyer updates.
        store.jdbc().queryForList("SELECT e.id FROM calendar_entries e JOIN calendar_recurrence_occurrences r ON r.calendar_entry_id=e.id WHERE r.party_id=? FOR UPDATE OF e",party.getId());
        var previous=store.jdbc().queryForList("SELECT r.*,e.location_id FROM calendar_recurrence_occurrences r JOIN calendar_entries e ON e.id=r.calendar_entry_id WHERE r.party_id=? AND NOT r.protected AND e.event_status='PLANNED' AND e.event_date>=? AND NOT EXISTS(SELECT 1 FROM editorial_post_drafts d WHERE d.calendar_entry_id=e.id)",party.getId(),today);
        for(var row:previous) {
            LocalDate date=Objects.requireNonNull((java.sql.Date)row.get("event_date")).toLocalDate();
            if(desired.contains(date) && Objects.equals(location,row.get("location_id"))) continue;
            calendar.deleteEntry((String)row.get("calendar_entry_id"));
            // Only this deliberate removal of an untouched system plan clears its tombstone.
            store.jdbc().update("DELETE FROM calendar_recurrence_occurrences WHERE party_id=? AND event_date=?",party.getId(),date);
        }
        if(location==null) return;
        for(LocalDate date:desired) {
            if(Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM calendar_recurrence_occurrences WHERE party_id=? AND event_date=?) OR EXISTS(SELECT 1 FROM calendar_entries WHERE party_id=? AND event_date=?) OR EXISTS(SELECT 1 FROM calendar_entries WHERE location_id=? AND event_date=? AND entry_type='closure')",Boolean.class,party.getId(),date,party.getId(),date,location,date))) continue;
            var entry=calendar.createPlannedEntry(new CalendarEntryRequest(date.toString(),location,"event","Geplant – nicht bestätigt. Fester Partyreihen-Turnus.","",party.getId(),null,null,Set.of()));
            store.jdbc().update("INSERT INTO calendar_recurrence_occurrences(party_id,event_date,calendar_entry_id) VALUES (?,?,?)",party.getId(),date,entry.id());
        }
    }
}
