"use client";

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import {
  toDateKey,
  useCatalog,
  type CalendarEntry,
} from "../CatalogProvider";
import EntryEditor from "./EntryEditor";
import { useModalFocus } from "../DashboardModal";

const weekdays = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"];

function sameDay(a: Date, b: Date) {
  return toDateKey(a) === toDateKey(b);
}

export default function CalendarPage() {
  const {
    entries,
    entriesLoading,
    entriesError,
    reloadEntries,
    deleteEntry: deleteCalendarEntry,
    parties,
    venues,
    actors,
  } = useCatalog();
  const [month, setMonth] = useState(new Date(2027, 0, 1));
  const [selectedDate, setSelectedDate] = useState<Date | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [actionError, setActionError] = useState("");
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [entrySearch, setEntrySearch] = useState("");
  const [calendarOpen, setCalendarOpen] = useState(true);
  const [infoEntryId, setInfoEntryId] = useState<string | null>(null);
  const [viewport, setViewport] = useState({ width: 0, height: 0 });
  const [editorScale, setEditorScale] = useState(1);
  const editorModalRef = useRef<HTMLDivElement | null>(null);

  const infoModalRef = useRef<HTMLDivElement | null>(null);
  useModalFocus(editorOpen && selectedDate !== null, editorModalRef, () => {
    setEditorOpen(false);
    setEditingId(null);
  });
  useModalFocus(entries.some((entry) => entry.id === infoEntryId), infoModalRef, () => setInfoEntryId(null));

  useEffect(() => {
    const updateViewport = () => {
      const visualViewport = window.visualViewport;
      setViewport({
        width: Math.round(visualViewport?.width ?? window.innerWidth),
        height: Math.round(visualViewport?.height ?? window.innerHeight),
      });
    };
    updateViewport();
    window.addEventListener("resize", updateViewport);
    window.visualViewport?.addEventListener("resize", updateViewport);
    return () => {
      window.removeEventListener("resize", updateViewport);
      window.visualViewport?.removeEventListener("resize", updateViewport);
    };
  }, []);

  useLayoutEffect(() => {
    const modal = editorModalRef.current;
    if (!editorOpen || !modal || viewport.width === 0 || viewport.height === 0) {
      setEditorScale(1);
      return;
    }

    const updateScale = () => {
      const availableWidth = Math.max(1, viewport.width - 32);
      const availableHeight = Math.max(1, viewport.height - 32);
      const scale = Math.min(
        1,
        availableWidth / modal.offsetWidth,
        availableHeight / modal.offsetHeight,
      );
      setEditorScale(scale);
    };

    updateScale();
    const observer = new ResizeObserver(updateScale);
    observer.observe(modal);
    return () => observer.disconnect();
  }, [editorOpen, editingId, selectedDate, viewport.width, viewport.height]);

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

  const entriesByDate = useMemo(() => {
    const result = new Map<string, CalendarEntry[]>();
    for (const entry of entries) {
      const existing = result.get(entry.date) ?? [];
      existing.push(entry);
      result.set(entry.date, existing);
    }
    return result;
  }, [entries]);

  const editingEntry = entries.find((item) => item.id === editingId) ?? null;
  const infoEntry = entries.find((item) => item.id === infoEntryId) ?? null;
  const infoVenue = infoEntry ? venues.find((venue) => venue.id === infoEntry.locationId) : undefined;
  const infoParty = infoEntry?.type === "event"
    ? parties.find((party) => party.id === infoEntry.partyId)
    : undefined;

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
  }, [actors, entrySearch, entries, parties, venues]);

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
    const date = selectedDate ?? new Date();
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
    selectDay(new Date());
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
          <button
            type="button"
            onClick={goToToday}
            className="rounded-lg border border-white/20 px-4 py-2 text-sm hover:bg-white/10"
          >
            Heute
          </button>
        </header>

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
                  const isToday = sameDay(date, new Date());
                  const dayEntries = entriesByDate.get(toDateKey(date)) ?? [];

                  return (
                    <div
                      key={toDateKey(date)}
                      onClick={() => selectDay(date)}
                      onDoubleClick={() => openNewEntryForDay(date)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault();
                          selectDay(date);
                        }
                      }}
                      role="button"
                      tabIndex={0}
                      aria-pressed={isSelected}
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
                      <span className="block text-sm font-medium">
                        {date.getDate()}
                      </span>

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
                            {entry.type === "event" && entry.startTime
                              ? `${entry.startTime} `
                              : ""}
                            {getEntryName(entry)}
                          </button>
                        ))}
                        {dayEntries.length > 2 && (
                          <span className="block text-[10px] text-neutral-400 sm:text-xs">
                            +{dayEntries.length - 2} weitere
                          </span>
                        )}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </section>

        <section className="mt-6 rounded-2xl border border-white/10 bg-neutral-950 p-4 sm:p-6">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <h2 className="text-xl font-semibold sm:text-2xl">
                Eingetragene Parties
              </h2>
              <p className="mt-1 text-sm text-neutral-400">
                Alle Veranstaltungen und Location-Sperren direkt bearbeiten oder löschen.
              </p>
            </div>
            <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row sm:items-center">
              <label className="w-full sm:w-72">
                <span className="sr-only">Kalendereinträge durchsuchen</span>
                <input
                  type="search"
                  value={entrySearch}
                  onChange={(event) => setEntrySearch(event.target.value)}
                  placeholder="Partys, Locations oder Datum suchen ..."
                  className="min-h-12 w-full rounded-lg border border-white/15 bg-neutral-900 px-3 py-2.5 text-sm text-white outline-none focus:border-white/60"
                />
              </label>
              <button
                type="button"
                onClick={openNewEntry}
                className="min-h-11 rounded-lg border border-white/15 px-4 py-2 text-sm hover:bg-white/10"
              >
                + Neuer Eintrag
              </button>
            </div>
          </div>

          <div className="mt-5 space-y-3">
            {entriesLoading ? (
              <p className="text-sm text-neutral-400" role="status">
                Einträge werden geladen ...
              </p>
            ) : entriesError ? (
              <p className="text-sm text-red-300" role="alert">
                Die Liste ist wegen des Ladefehlers nicht verfügbar.
              </p>
            ) : filteredEntries.length === 0 ? (
              <p className="rounded-lg border border-dashed border-white/10 p-4 text-sm text-neutral-400">
                {entrySearch.trim()
                  ? "Keine passenden Einträge gefunden."
                  : "Noch keine Einträge vorhanden."}
              </p>
            ) : (
              filteredEntries.map((entry) => {
                const locationName =
                  venues.find((venue) => venue.id === entry.locationId)?.name ??
                  "Unbekannte Location";
                const entryDate = dateFromKey(entry.date).toLocaleDateString("de-DE", {
                  weekday: "short",
                  day: "2-digit",
                  month: "2-digit",
                  year: "numeric",
                });

                return (
                  <article
                    key={entry.id}
                    className="flex flex-col gap-4 rounded-xl border border-white/10 bg-neutral-900 p-4 sm:flex-row sm:items-center sm:justify-between"
                    style={{ borderLeftColor: getEntryColor(entry), borderLeftWidth: 4 }}
                  >
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <p className="font-medium">
                          {getEntryName(entry)}
                        </p>
                        <span className="rounded-full border border-white/15 px-2 py-0.5 text-[11px] text-neutral-400">
                          {entry.type === "event" ? "Party" : "Sperre"}
                        </span>
                      </div>
                      <p className="mt-1 text-sm text-neutral-300">
                        {entryDate} · {locationName}
                        {entry.type === "event" && entry.startTime
                          ? ` · ${entry.startTime} Uhr`
                          : ""}
                      </p>
                      {entry.type === "closure" && (
                        <p className="mt-1 text-xs text-neutral-500">
                          Grund: {entry.reason}
                        </p>
                      )}
                    </div>
                    <div className="flex shrink-0 gap-2">
                      <button
                        type="button"
                        onClick={() => editFromList(entry)}
                        className="min-h-11 rounded-lg border border-white/20 px-3 py-2 text-xs hover:bg-white/10"
                      >
                        Bearbeiten
                      </button>
                      <button
                        type="button"
                        onClick={() => void deleteEntry(entry)}
                        disabled={deletingId === entry.id}
                        className="min-h-11 rounded-lg border border-white/20 px-3 py-2 text-xs hover:bg-white/10 disabled:cursor-wait disabled:opacity-50"
                      >
                        {deletingId === entry.id ? "Löschen ..." : "Löschen"}
                      </button>
                    </div>
                  </article>
                );
              })
            )}
          </div>
        </section>

        {editorOpen && selectedDate && (
          <div
            className="fixed inset-0 z-50 flex items-center justify-center overflow-hidden bg-black/80 p-4 backdrop-blur-sm sm:p-8"
            role="presentation"
          >
            <div
              ref={editorModalRef}
              tabIndex={-1}
              className="w-full max-w-3xl rounded-2xl border border-white/15 bg-black p-1 shadow-2xl"
              style={{ transform: `scale(${editorScale})`, transformOrigin: "center center" }}
              role="dialog"
              aria-modal="true"
              aria-labelledby="calendar-entry-dialog-title"
            >
              <div className="flex justify-end px-4 pt-4 sm:px-6">
                <button
                  type="button"
                  data-modal-close
                  onClick={() => {
                    setEditorOpen(false);
                    setEditingId(null);
                  }}
                  className="rounded-lg border border-white/20 px-3 py-2 text-sm text-neutral-300 hover:bg-white/10 hover:text-white"
                >
                  Schließen
                </button>
              </div>
              <div id="calendar-entry-dialog-title" className="sr-only">
                Kalendereintrag {editingEntry ? "bearbeiten" : "anlegen"}
              </div>
              <EntryEditor
                key={`${toDateKey(selectedDate)}-${editingId ?? "new"}`}
                date={selectedDate}
                entry={editingEntry}
                onSaved={() => {
                  setEditorOpen(false);
                  setEditingId(null);
                }}
                onCancel={() => {
                  setEditorOpen(false);
                  setEditingId(null);
                }}
              />
            </div>
          </div>
        )}

        {infoEntry && (
          <div
            className="fixed inset-0 z-40 flex items-center justify-center bg-black/80 p-4 backdrop-blur-sm"
            role="presentation"
            onClick={() => setInfoEntryId(null)}
          >
            <div
              ref={infoModalRef}
              tabIndex={-1}
              className="max-h-[calc(100dvh-2rem)] w-full max-w-lg overflow-y-auto rounded-2xl border border-white/15 bg-neutral-950 p-5 shadow-2xl sm:p-6"
              role="dialog"
              aria-modal="true"
              aria-labelledby="calendar-entry-info-title"
              onClick={(event) => event.stopPropagation()}
            >
              <div className="flex items-start justify-between gap-4">
                <div>
                  <p className="text-xs uppercase tracking-[0.18em] text-neutral-500">{infoEntry.type === "event" ? "Veranstaltung" : "Location-Sperre"}</p>
                  <h2 id="calendar-entry-info-title" className="mt-1 text-2xl font-semibold">{getEntryName(infoEntry)}</h2>
                  <p className="mt-1 text-sm text-neutral-400">{dateFromKey(infoEntry.date).toLocaleDateString("de-DE", { weekday: "long", day: "2-digit", month: "long", year: "numeric" })}</p>
                </div>
                <button type="button" onClick={() => setInfoEntryId(null)} className="rounded-lg border border-white/20 px-3 py-2 text-sm hover:bg-white/10">Schließen</button>
              </div>
              <dl className="mt-5 space-y-3 text-sm">
                <div><dt className="text-neutral-500">Location</dt><dd className="mt-1 text-white">{infoVenue?.name ?? "Unbekannte Location"}</dd></div>
                {infoEntry.type === "event" && infoEntry.startTime && <div><dt className="text-neutral-500">Beginn</dt><dd className="mt-1 text-white">{infoEntry.startTime} Uhr</dd></div>}
                {infoParty && <div><dt className="text-neutral-500">Partyreihe</dt><dd className="mt-1 text-white">{infoParty.name}</dd></div>}
                {infoEntry.type === "event" && infoEntry.entryPrice && <div><dt className="text-neutral-500">Eintritt</dt><dd className="mt-1 text-white">{infoEntry.entryPrice}</dd></div>}
                {infoEntry.type === "closure" && <div><dt className="text-neutral-500">Grund</dt><dd className="mt-1 text-white">{infoEntry.reason}</dd></div>}
                {infoEntry.notes && <div><dt className="text-neutral-500">Notizen</dt><dd className="mt-1 whitespace-pre-wrap text-white">{infoEntry.notes}</dd></div>}
              </dl>
              <div className="mt-6 flex justify-end gap-2">
                <button type="button" onClick={() => { setInfoEntryId(null); editFromList(infoEntry); }} className="min-h-11 rounded-lg border border-white/15 px-4 py-2 text-sm hover:bg-white/10">Bearbeiten</button>
              </div>
            </div>
          </div>
        )}

        <p className="mt-6 text-xs text-neutral-500">
          Automatische Termine aus dem Turnus werden nicht erzeugt.
        </p>
      </div>
    </main>
  );
}
