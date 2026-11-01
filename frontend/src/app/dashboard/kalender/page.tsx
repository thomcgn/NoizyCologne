"use client";

import { useMemo, useState } from "react";
import {
  toDateKey,
  useCatalog,
  type CalendarEntry,
} from "../CatalogProvider";
import EntryEditor from "./EntryEditor";

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
  } = useCatalog();
  const [month, setMonth] = useState(new Date(2027, 0, 1));
  const [selectedDate, setSelectedDate] = useState<Date | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [actionError, setActionError] = useState("");
  const [deletingId, setDeletingId] = useState<string | null>(null);

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

  const selectedKey = selectedDate ? toDateKey(selectedDate) : null;
  const selectedEntries = selectedKey
    ? (entriesByDate.get(selectedKey) ?? [])
    : [];
  const editingEntry = entries.find((item) => item.id === editingId) ?? null;

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
    setEditorOpen(true);

    if (
      date.getMonth() !== month.getMonth() ||
      date.getFullYear() !== month.getFullYear()
    ) {
      setMonth(new Date(date.getFullYear(), date.getMonth(), 1));
    }
  }

  function goToToday() {
    selectDay(new Date());
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
            <h2 className="text-xl font-semibold capitalize sm:text-2xl">
              {month.toLocaleDateString("de-DE", {
                month: "long",
                year: "numeric",
              })}
            </h2>
            <div className="flex gap-1.5 sm:gap-2">
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
            </div>
          </div>

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
              const dayEntries = entriesByDate.get(toDateKey(date)) ?? [];

              return (
                <button
                  key={toDateKey(date)}
                  type="button"
                  onClick={() => selectDay(date)}
                  aria-pressed={isSelected}
                  aria-label={`${date.toLocaleDateString("de-DE", {
                    weekday: "long",
                    day: "numeric",
                    month: "long",
                    year: "numeric",
                  })}, ${dayEntries.length} Einträge`}
                  className={[
                    "min-h-16 min-w-0 p-1 text-left transition-colors sm:min-h-28 sm:p-2",
                    inMonth
                      ? isWeekend
                        ? "bg-neutral-900"
                        : "bg-neutral-950"
                      : "bg-black text-neutral-600",
                    isSelected
                      ? "ring-2 ring-inset ring-white"
                      : "hover:bg-neutral-800",
                  ].join(" ")}
                >
                  <span className="block text-sm font-medium">
                    {date.getDate()}
                  </span>

                  <span className="mt-1 block space-y-1">
                    {dayEntries.slice(0, 2).map((entry) => (
                      <span
                        key={entry.id}
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
                      </span>
                    ))}
                    {dayEntries.length > 2 && (
                      <span className="block text-[10px] text-neutral-400 sm:text-xs">
                        +{dayEntries.length - 2} weitere
                      </span>
                    )}
                  </span>
                </button>
              );
            })}
          </div>
        </section>

        {selectedDate ? (
          <>
            <section className="mt-6 rounded-xl border border-white/10 bg-neutral-950 p-5 sm:p-6">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h2 className="text-lg font-semibold">
                    {selectedDate.toLocaleDateString("de-DE", {
                      weekday: "long",
                      day: "numeric",
                      month: "long",
                      year: "numeric",
                    })}
                  </h2>
                  <p className="mt-1 text-sm text-neutral-400">
                    {selectedEntries.length === 0
                      ? entriesError
                        ? "Kalendereinträge sind derzeit nicht verfügbar."
                        : "Noch keine Einträge für diesen Tag."
                      : `${selectedEntries.length} Eintrag/Einträge`}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    setEditingId(null);
                    setEditorOpen(true);
                  }}
                  className="rounded-lg border border-white/20 px-4 py-2 text-sm hover:bg-white/10"
                >
                  + Neuer Eintrag
                </button>
              </div>

              {selectedEntries.length > 0 && (
                <div className="mt-4 space-y-3">
                  {selectedEntries.map((entry) => (
                    <div
                      key={entry.id}
                      className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-white/10 bg-neutral-900 p-3"
                      style={{ borderLeftColor: getEntryColor(entry), borderLeftWidth: 4 }}
                    >
                      <div className="min-w-0">
                        <p className="font-medium">{getEntryName(entry)}</p>
                        <p className="mt-1 text-xs text-neutral-400">
                          {venues.find((venue) => venue.id === entry.locationId)
                            ?.name ?? "Unbekannte Location"}
                          {entry.type === "event" && entry.startTime
                            ? ` · ${entry.startTime} Uhr`
                            : ""}
                        </p>
                      </div>
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={() => {
                            setEditingId(entry.id);
                            setEditorOpen(true);
                          }}
                          className="rounded-lg border border-white/20 px-3 py-2 text-xs hover:bg-white/10"
                        >
                          Bearbeiten
                        </button>
                        <button
                          type="button"
                          onClick={() => void deleteEntry(entry)}
                          disabled={deletingId === entry.id}
                          className="rounded-lg border border-white/20 px-3 py-2 text-xs hover:bg-white/10"
                        >
                          {deletingId === entry.id ? "Löschen ..." : "Löschen"}
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>

            {editorOpen && (
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
            )}
          </>
        ) : (
          <section className="mt-6 rounded-xl border border-white/10 bg-neutral-950 p-6">
            <p className="text-sm text-neutral-400">
              Wähle einen Tag aus, um Kalendereinträge anzulegen oder zu bearbeiten.
            </p>
          </section>
        )}

        <p className="mt-6 text-xs text-neutral-500">
          Automatische Termine aus dem Turnus werden nicht erzeugt.
        </p>
      </div>
    </main>
  );
}
