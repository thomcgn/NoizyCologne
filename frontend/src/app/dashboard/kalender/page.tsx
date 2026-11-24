"use client";

import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import Image from "next/image";
import { berlinDate } from "./calendarNavigation";
import { useDayPreview } from "./useDayPreview";
import {
  toDateKey,
  useCatalog,
  type CalendarEntry,
} from "../CatalogProvider";
import EntryEditor from "./EntryEditor";
import DashboardModal from "../DashboardModal";
import DataCollection, { type CollectionColumn } from "../DataCollection";
import { useWorkspaceAction } from "../workspaceActions";

const weekdays = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"];

function sameDay(a: Date, b: Date) {
  return toDateKey(a) === toDateKey(b);
}

function CalendarContent() {
  const searchParams = useSearchParams();
  const targetId = searchParams.get("entry");
  const openedTarget = useRef<string | null>(null);
  const {
    entries,
    entriesLoading,
    entriesError,
    reloadEntries,
    reloadParties,
    deleteEntry: deleteCalendarEntry,
    parties,
    venues,
    actors,
  } = useCatalog();
  const [month, setMonth] = useState(() => { const today = berlinDate(); return new Date(today.getFullYear(), today.getMonth(), 1); });
  const [selectedDate, setSelectedDate] = useState<Date | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [actionError, setActionError] = useState("");
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [entrySearch, setEntrySearch] = useState("");
  const [calendarOpen, setCalendarOpen] = useState(true);
  const [infoEntryId, setInfoEntryId] = useState<string | null>(null);
  const [typeFilter, setTypeFilter] = useState("all");
  const [locationFilter, setLocationFilter] = useState("");

  // The dashboard provider already loads entries and parties once at mount.
  // Repeating that here caused duplicate requests; partial failures are still
  // reported independently by the provider's loaders.
  useEffect(() => {
    if (!targetId || entriesLoading || openedTarget.current === targetId) return;
    const entry = entries.find(item => item.id === targetId);
    if (!entry) return;
    const timer = window.setTimeout(() => {
      openedTarget.current = targetId;
      const [year, month, day] = entry.date.split("-").map(Number);
      setMonth(new Date(year, month - 1, 1));
      setSelectedDate(new Date(year, month - 1, day));
      setTypeFilter("all");
      setLocationFilter("");
      setEntrySearch("");
      setInfoEntryId(entry.id);
    }, 0);
    return () => window.clearTimeout(timer);
  }, [targetId, entries, entriesLoading]);

  const days = useMemo(() => {
    const year = month.getFullYear();
    const monthIndex = month.getMonth();
    const offset = (new Date(year, monthIndex, 1).getDay() + 6) % 7;
    const daysInMonth = new Date(year, monthIndex + 1, 0).getDate();
    const totalCells = Math.ceil((offset + daysInMonth) / 7) * 7;

    return Array.from({ length: totalCells }, (_, index) =>
      new Date(year, monthIndex, index - offset + 1)
    );
  }, [month]);

  const editingEntry = entries.find((item) => item.id === editingId) ?? null;
  const infoEntry = entries.find((item) => item.id === infoEntryId) ?? null;
  const infoVenue = infoEntry ? venues.find((venue) => venue.id === infoEntry.locationId) : undefined;
  const infoParty = infoEntry?.type === "event"
    ? parties.find((party) => party.id === infoEntry.partyId)
    : undefined;
  const infoFlyer = infoEntry?.type === "event" ? infoEntry.eventFlyer ?? infoParty?.defaultFlyer : null;

  function getEntryName(entry: CalendarEntry) {
    if (entry.type === "closure") return entry.reason;
    return parties.find((party) => party.id === entry.partyId)?.name ??
      "Unbekannte Partyreihe";
  }

  function getEntryColor(entry: CalendarEntry) {
    if (entry.type === "closure") return "#9CA3AF";
    return parties.find((party) => party.id === entry.partyId)?.color ??
      "#9CA3AF";
  }

  const filteredEntries = useMemo(() => {
    const query = entrySearch.trim().toLocaleLowerCase("de-DE");
    const matchingEntries = entries.filter((entry) => {
      if (typeFilter !== "all" && entry.type !== typeFilter) return false;
      if (locationFilter && entry.locationId !== locationFilter) return false;
      if (!query) return true;

      const partyName =
        entry.type === "event"
          ? parties.find((party) => party.id === entry.partyId)?.name ?? ""
          : "";
      const locationName =
        venues.find((venue) => venue.id === entry.locationId)?.name ?? "";
      const mentionedDjs =
        entry.type === "event"
          ? actors
              .filter((actor) => entry.mentionDjIds.includes(actor.id))
              .map((actor) => `${actor.name} ${actor.instagram ?? ""}`)
              .join(" ")
          : "";
      const searchableText = [
        entry.date,
        entry.type === "closure" ? entry.reason : "Party",
        partyName,
        locationName,
        entry.notes,
        mentionedDjs,
        entry.type === "event" ? entry.startTime : "",
      ]
        .join(" ")
        .toLocaleLowerCase("de-DE");

      return searchableText.includes(query);
    });

    return [...matchingEntries].sort((first, second) => {
      const firstKey = `${first.date} ${first.type === "event" ? first.startTime : "23:59"}`;
      const secondKey = `${second.date} ${second.type === "event" ? second.startTime : "23:59"}`;
      return firstKey.localeCompare(secondKey);
    });
  }, [actors, entrySearch, entries, parties, venues, typeFilter, locationFilter]);

  const entriesByDate = useMemo(() => {
    const result = new Map<string, CalendarEntry[]>();
    for (const entry of filteredEntries) {
      const existing = result.get(entry.date) ?? [];
      existing.push(entry);
      result.set(entry.date, existing);
    }
    return result;
  }, [filteredEntries]);

  const previewScope = useMemo(() => ({ entriesByDate, month, calendarOpen, infoEntryId, editorOpen, entriesLoading, entriesError }), [entriesByDate, month, calendarOpen, infoEntryId, editorOpen, entriesLoading, entriesError]);
  const preview = useDayPreview(previewScope, calendarOpen && !infoEntryId && !editorOpen && !entriesLoading && !entriesError);
  const previewEntries = [...(entriesByDate.get(preview.date ?? "") ?? [])].sort((a, b) => {
    const first = a.type === "event" ? a.startTime : "";
    const second = b.type === "event" ? b.startTime : "";
    return (first || "99:99").localeCompare(second || "99:99");
  });

  function dateFromKey(dateKey: string): Date {
    const [year, monthIndex, day] = dateKey.split("-").map(Number);
    return new Date(year, monthIndex - 1, day);
  }

  function editFromList(entry: CalendarEntry) {
    const date = dateFromKey(entry.date);
    setMonth(new Date(date.getFullYear(), date.getMonth(), 1));
    setSelectedDate(date);
    setEditingId(entry.id);
    setEditorOpen(true);
  }

  function openNewEntry() {
    const date = selectedDate ?? berlinDate();
    setMonth(new Date(date.getFullYear(), date.getMonth(), 1));
    setSelectedDate(date);
    setEditingId(null);
    setEditorOpen(true);
  }

  function changeMonth(amount: number) {
    setMonth((current) =>
      new Date(current.getFullYear(), current.getMonth() + amount, 1)
    );
    setSelectedDate(null);
    setEditorOpen(false);
    setEditingId(null);
  }

  function selectDay(date: Date) {
    setSelectedDate(date);
    setEditingId(null);
    setEditorOpen(false);

    if (
      date.getMonth() !== month.getMonth() ||
      date.getFullYear() !== month.getFullYear()
    ) {
      setMonth(new Date(date.getFullYear(), date.getMonth(), 1));
    }
  }

  function openNewEntryForDay(date: Date) {
    selectDay(date);
    setEditingId(null);
    setEditorOpen(true);
  }

  function goToToday() {
    selectDay(berlinDate());
  }

  function toggleCalendar() {
    if (calendarOpen) {
      goToToday();
    }
    setCalendarOpen((open) => !open);
  }

  async function deleteEntry(entry: CalendarEntry) {
    if (!window.confirm(`„${getEntryName(entry)}“ wirklich löschen?`)) {
      return;
    }

    setActionError("");
    setDeletingId(entry.id);
    try {
      await deleteCalendarEntry(entry.id);
      if (editingId === entry.id) {
        setEditingId(null);
        setEditorOpen(false);
      }
    } catch (cause) {
      setActionError(
        cause instanceof Error
          ? `Kalendereintrag konnte nicht gelöscht werden: ${cause.message}`
          : "Kalendereintrag konnte wegen eines unbekannten Netzwerkfehlers nicht gelöscht werden."
      );
    } finally {
      setDeletingId(null);
    }
  }

  useWorkspaceAction("Neuer Eintrag", openNewEntry, entriesLoading || !!entriesError);
  const columns: CollectionColumn<CalendarEntry>[] = [
    {key: "name", label: "Veranstaltung / Sperre", sortValue: getEntryName, render: entry => <strong className="cc-calendar-name"><span aria-hidden="true" className="cc-color-dot" style={{backgroundColor: getEntryColor(entry)}} />{getEntryName(entry)}</strong>},
    {key: "date", label: "Datum", sortValue: entry => `${entry.date} ${entry.type === "event" ? entry.startTime : "23:59"}`, render: entry => dateFromKey(entry.date).toLocaleDateString("de-DE", {weekday: "short", day: "2-digit", month: "2-digit", year: "numeric"})},
    {key: "location", label: "Location", sortValue: entry => venues.find(venue => venue.id === entry.locationId)?.name ?? "", render: entry => venues.find(venue => venue.id === entry.locationId)?.name ?? "Unbekannte Location"},
    {key: "time", label: "Beginn", render: entry => entry.type === "event" ? entry.startTime || "Noch offen" : "–"},
    {key: "type", label: "Art / Status", render: entry => <span className={`cc-status ${entry.eventStatus === "CANCELLED" ? "cc-status-danger" : ""}`}>{entry.eventStatus === "CANCELLED" ? "ABGESAGT" : entry.eventStatus === "PLANNED" ? "Geplant – nicht bestätigt" : entry.type === "closure" ? "Sperre" : "Party"}</span>},
    {key: "price", label: "Eintritt", render: entry => entry.type === "event" ? entry.entryPrice || "Noch offen" : "–"},
  ];

  return (
    <main className="app-page min-h-dvh bg-black px-4 py-6 text-white sm:py-8 md:px-10">
      <div className="mx-auto max-w-6xl">
        <header className="mt-6 flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-xs uppercase tracking-[0.2em] text-neutral-500">
              NoizyCologne / Kalender
            </p>
            <h1 className="mt-2 text-3xl font-bold">Veranstaltungskalender</h1>
            <p className="mt-2 text-sm text-neutral-400">
              Veranstaltungen und Location-Sperren werden dauerhaft gespeichert.
            </p>
          </div>
          <button type="button" disabled={entriesLoading} onClick={() => { void reloadEntries(); void reloadParties(); }} className="cc-button">Aktualisieren</button>
          <button
            type="button"
            onClick={goToToday}
            className="rounded-lg border border-white/20 px-4 py-2 text-sm hover:bg-white/10"
          >
            Heute
          </button>
        </header>

        {targetId && !entriesLoading && !entriesError && !entries.some(entry => entry.id === targetId) && <p role="alert" className="mt-6">Der verlinkte Kalendereintrag ist nicht mehr vorhanden.</p>}
        {entriesLoading && (
          <p className="mt-6 text-sm text-neutral-400" role="status">
            Kalendereinträge werden aus PostgreSQL geladen ...
          </p>
        )}
        {entriesError && (
          <div className="mt-6 rounded-xl border border-red-500/30 bg-red-950/20 p-4" role="alert">
            <p className="text-sm text-red-300">{entriesError}</p>
            <button
              type="button"
              onClick={() => void reloadEntries()}
              className="mt-3 rounded-lg border border-white/20 px-4 py-2 text-sm hover:bg-white/10"
            >
              Erneut versuchen
            </button>
          </div>
        )}
        {actionError && (
          <p className="mt-6 text-sm text-red-400" role="alert">
            {actionError}
          </p>
        )}

        <section className="mt-8 rounded-2xl border border-white/10 bg-neutral-950 p-3 sm:p-6">
          <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="text-xl font-semibold capitalize sm:text-2xl">
                {selectedDate
                  ? selectedDate.toLocaleDateString("de-DE", {
                      weekday: "long",
                      day: "numeric",
                      month: "long",
                      year: "numeric",
                    })
                  : month.toLocaleDateString("de-DE", {
                      month: "long",
                      year: "numeric",
                    })}
              </h2>
            </div>
            <div className="flex flex-wrap justify-end gap-1.5 sm:gap-2">
              {calendarOpen && (
                <>
                  <button
                    type="button"
                    onClick={() => changeMonth(-1)}
                    aria-label="Vorheriger Monat"
                    className="rounded-lg border border-white/20 px-4 py-2 hover:bg-white/10"
                  >
                    ←
                  </button>
                  <button
                    type="button"
                    onClick={() => changeMonth(1)}
                    aria-label="Nächster Monat"
                    className="rounded-lg border border-white/20 px-4 py-2 hover:bg-white/10"
                  >
                    →
                  </button>
                </>
              )}
              <button
                type="button"
                onClick={toggleCalendar}
                aria-expanded={calendarOpen}
                aria-controls="calendar-month-view"
                className="rounded-lg border border-white/20 px-4 py-2 text-sm hover:bg-white/10"
              >
                {calendarOpen ? "Kalender ausblenden" : "Kalender einblenden"}
              </button>
            </div>
          </div>

          {calendarOpen && (
            <div id="calendar-month-view">
              <div className="mb-2 grid grid-cols-7 text-center">
                {weekdays.map((day) => (
                  <div
                    key={day}
                    className="py-2 text-xs font-medium text-neutral-400 sm:text-sm"
                  >
                    {day}
                  </div>
                ))}
              </div>

              <div className="grid grid-cols-7 gap-px overflow-hidden rounded-xl border border-white/10 bg-white/10">
                {days.map((date) => {
                  const inMonth = date.getMonth() === month.getMonth();
                  const isWeekend = [0, 6].includes(date.getDay());
                  const isSelected =
                    selectedDate !== null && sameDay(date, selectedDate);
                  const isToday = sameDay(date, berlinDate());
                  const dayEntries = entriesByDate.get(toDateKey(date)) ?? [];

                  return (
                    <div
                      key={toDateKey(date)}
                      onClick={() => selectDay(date)}
                      onDoubleClick={() => openNewEntryForDay(date)}
                      role="group"
                      data-calendar-day={toDateKey(date)}
                      {...preview.handlers(toDateKey(date), dayEntries.length > 0)}
                      aria-label={`${date.toLocaleDateString("de-DE", {
                        weekday: "long",
                        day: "numeric",
                        month: "long",
                        year: "numeric",
                      })}, ${dayEntries.length} Einträge. Doppelklick öffnet einen neuen Eintrag.`}
                      className={[
                        "min-h-16 min-w-0 p-1 text-left transition-colors sm:min-h-28 sm:p-2",
                        inMonth
                          ? isWeekend
                            ? "bg-neutral-900"
                            : "bg-neutral-950"
                          : "bg-black text-neutral-600",
                        isSelected
                          ? isToday
                            ? "ring-1 ring-inset ring-red-400"
                            : "ring-2 ring-inset ring-white"
                          : isToday
                            ? "ring-1 ring-inset ring-red-400"
                            : "hover:bg-neutral-800",
                      ].join(" ")}
                    >
                      <button type="button" data-day-select aria-pressed={isSelected}
                        aria-label={date.toLocaleDateString("de-DE", { weekday: "long", day: "numeric", month: "long", year: "numeric" })}
                        className="block rounded-sm text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-2">
                        {date.getDate()}
                      </button>

                      <span className="mt-1 block space-y-1">
                        {dayEntries.slice(0, 2).map((entry) => (
                          <button
                            key={entry.id}
                            type="button"
                            onClick={(event) => {
                              event.stopPropagation();
                              setInfoEntryId(entry.id);
                            }}
                            onDoubleClick={(event) => event.stopPropagation()}
                            className="block truncate rounded-sm border-l-[3px] bg-white/5 px-1 py-1 text-[10px] leading-tight text-white sm:text-xs"
                            style={{
                              borderLeftColor: getEntryColor(entry),
                              backgroundColor: `${getEntryColor(entry)}22`,
                            }}
                            title={getEntryName(entry)}
                          >
                            {entry.eventStatus === "PLANNED" && <strong>Geplant – nicht bestätigt · </strong>}
                            {entry.eventStatus === "CANCELLED" && <strong className="text-red-400">ABGESAGT · </strong>}
                            {entry.type === "event" && entry.startTime
                              ? `${entry.startTime} `
                              : ""}
                            {getEntryName(entry)}
                          </button>
                        ))}
                        {dayEntries.length > 0 && (
                          <button type="button" className="block max-w-full rounded-sm text-left text-[10px] leading-tight text-neutral-300 underline focus-visible:outline-2 focus-visible:outline-offset-2 sm:text-xs"
                            onClick={event => { event.stopPropagation(); const cell = event.currentTarget.closest<HTMLElement>("[data-calendar-day]"); if (cell) preview.open(toDateKey(date), cell); }}
                            onDoubleClick={event => event.stopPropagation()} aria-haspopup="dialog">
                            Alle {dayEntries.length} ansehen
                          </button>
                        )}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </section>

        <section className="cc-catalog-list" aria-label="Kalendereinträge">
          <div className="cc-section-title cc-list-heading"><div><h2>Eingetragene Partys &amp; Sperren</h2><p className="cc-description">Alle Termine suchen, prüfen und bearbeiten.</p></div><button type="button" className="cc-button cc-button-primary cc-page-primary" onClick={openNewEntry} disabled={entriesLoading || !!entriesError}>+ Neuer Eintrag</button></div>
          {entriesLoading ? <p className="cc-empty" role="status">Einträge werden geladen …</p>
          : entriesError ? <p className="cc-message" role="alert">Die Liste ist wegen des Ladefehlers nicht verfügbar.</p>
          : <DataCollection items={filteredEntries} columns={columns} label="Kalendereinträge" storageKey="noizy-view-calendar" getName={getEntryName} onOpen={entry => setInfoEntryId(entry.id)} openDisabled={deletingId !== null} initialSortKey="date" query={entrySearch} onQueryChange={setEntrySearch} searchLabel="Kalendereinträge durchsuchen" empty={entries.length ? "Keine passenden Einträge gefunden." : "Noch keine Einträge vorhanden."} toolbar={<><label className="cc-inline-filter">Art<select className="cc-input" value={typeFilter} onChange={event => setTypeFilter(event.target.value)}><option value="all">Alle</option><option value="event">Partys</option><option value="closure">Sperren</option></select></label><label className="cc-inline-filter">Location<select className="cc-input" value={locationFilter} onChange={event => setLocationFilter(event.target.value)}><option value="">Alle</option>{venues.map(venue => <option key={venue.id} value={venue.id}>{venue.name}</option>)}</select></label></>} actions={entry => <div className="cc-inline-actions"><button type="button" className="cc-button" disabled={deletingId !== null} onClick={() => editFromList(entry)}>Bearbeiten</button><button type="button" className="cc-button cc-button-danger" onClick={() => void deleteEntry(entry)} disabled={deletingId !== null}>{deletingId === entry.id ? "Löschen ..." : "Löschen"}</button></div>} />}
        </section>

        {preview.date && previewEntries.length > 0 && <DashboardModal open onClose={preview.close}
          title={dateFromKey(preview.date).toLocaleDateString("de-DE", { weekday: "long", day: "2-digit", month: "2-digit", year: "numeric" })}
          description={`${previewEntries.length} ${previewEntries.length === 1 ? "Veranstaltung" : "Veranstaltungen"}`}>
          <ul className="calendar-day-preview">
            {previewEntries.map(entry => {
              const location = venues.find(venue => venue.id === entry.locationId)?.name;
              return <li key={entry.id}>
                <button type="button" onClick={() => { preview.close(); setInfoEntryId(entry.id); }}
                  className="calendar-day-preview-entry" style={{ borderLeftColor: getEntryColor(entry) }}>
                  <span className="font-semibold">{entry.type === "event" && entry.startTime && <span>{entry.startTime} · </span>}{getEntryName(entry)}</span>
                  {location && <span className="text-sm text-neutral-400">{location}</span>}
                  <span className={`text-xs ${entry.eventStatus === "CANCELLED" ? "text-red-400" : "text-neutral-400"}`}>
                    {entry.type === "closure" ? "Location-Sperre" : "Party"}{entry.eventStatus === "CANCELLED" ? " · ABGESAGT" : entry.eventStatus === "PLANNED" ? " · Geplant – nicht bestätigt" : ""}
                  </span>
                </button>
              </li>;
            })}
          </ul>
        </DashboardModal>}

        {editorOpen && selectedDate && <DashboardModal variant="drawer" protectChanges open onClose={() => {setEditorOpen(false); setEditingId(null);}} title={editingEntry ? "Kalendereintrag bearbeiten" : "Neuer Kalendereintrag"}>
          <EntryEditor key={`${toDateKey(selectedDate)}-${editingId ?? "new"}`} date={selectedDate} entry={editingEntry} onSaved={() => {setEditorOpen(false); setEditingId(null);}} onCancel={() => {setEditorOpen(false); setEditingId(null);}} />
        </DashboardModal>}

        {infoEntry && <DashboardModal variant="drawer" open onClose={() => setInfoEntryId(null)} title={getEntryName(infoEntry)} description={dateFromKey(infoEntry.date).toLocaleDateString("de-DE", {weekday: "long", day: "2-digit", month: "long", year: "numeric"})}>
          <span className={`cc-status ${infoEntry.eventStatus === "CANCELLED" ? "cc-status-danger" : ""}`}>{infoEntry.eventStatus === "CANCELLED" ? "ABGESAGT" : infoEntry.eventStatus === "PLANNED" ? "Geplant – nicht bestätigt" : infoEntry.type === "closure" ? "Location-Sperre" : "Veranstaltung"}</span>
          {infoEntry.type === "event" && <figure className="mt-5 rounded-xl border border-white/10 bg-black/10 p-3">
            {infoFlyer ? <><a href={infoFlyer.url} target="_blank" rel="noreferrer" aria-label="Flyer größer öffnen">
              <Image src={infoFlyer.url} width={1200} height={1200} unoptimized alt={`${infoEntry.eventFlyer ? "Event-Flyer" : "Dauerflyer"} ${getEntryName(infoEntry)}`} className="mx-auto object-contain" style={{ width: "auto", height: "auto", maxWidth: "100%", maxHeight: "65vh" }} />
            </a><figcaption className="mt-2 text-center text-xs text-neutral-500">Flyer zum Vergrößern öffnen</figcaption></> : <p className="py-8 text-center text-sm text-neutral-500">Noch kein Flyer hinterlegt</p>}
          </figure>}
              <dl className="mt-5 space-y-3 text-sm">
                <div><dt className="text-neutral-500">Location</dt><dd className="mt-1 text-white">{infoVenue?.name ?? "Unbekannte Location"}</dd></div>
                {infoEntry.type === "event" && infoEntry.startTime && <div><dt className="text-neutral-500">Beginn</dt><dd className="mt-1 text-white">{infoEntry.startTime} Uhr</dd></div>}
                {infoParty && <div><dt className="text-neutral-500">Partyreihe</dt><dd className="mt-1 text-white">{infoParty.name}</dd></div>}
                {infoEntry.type === "event" && infoEntry.entryPrice && <div><dt className="text-neutral-500">Eintritt</dt><dd className="mt-1 text-white">{infoEntry.entryPrice}</dd></div>}
                {infoEntry.type === "closure" && <div><dt className="text-neutral-500">Grund</dt><dd className="mt-1 text-white">{infoEntry.reason}</dd></div>}
                {infoEntry.type === "closure" && infoEntry.notes && <div><dt className="text-neutral-500">Notizen</dt><dd className="mt-1 whitespace-pre-wrap text-white">{infoEntry.notes}</dd></div>}
              </dl>
          <div className="mt-6 flex justify-end"><button type="button" className="cc-button cc-button-primary" onClick={() => {setInfoEntryId(null); editFromList(infoEntry);}}>Bearbeiten</button></div>
        </DashboardModal>}

        <p className="mt-6 text-xs text-neutral-500">
          Bestätigte feste Turnusregeln planen drei Monate voraus. Plantermine sind keine bestätigten Veranstaltungen.
        </p>
      </div>
    </main>
  );
}

export default function CalendarPage() {
  return <Suspense fallback={<p role="status">Kalender wird geladen …</p>}><CalendarContent /></Suspense>;
}
