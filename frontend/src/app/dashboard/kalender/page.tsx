
"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import EntryEditor from "./EntryEditor";

const weekdays = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"];

function sameDay(a: Date, b: Date) {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

export default function CalendarPage() {
  const [month, setMonth] = useState(new Date(2027, 0, 1));
  const [selectedDate, setSelectedDate] = useState<Date | null>(null);

  const days = useMemo(() => {
    const year = month.getFullYear();
    const monthIndex = month.getMonth();

    // Montag = 0, Sonntag = 6
    const offset = (
      new Date(year, monthIndex, 1).getDay() + 6
    ) % 7;

    const daysInMonth = new Date(
      year,
      monthIndex + 1,
      0
    ).getDate();

    const totalCells =
      Math.ceil((offset + daysInMonth) / 7) * 7;

    return Array.from({ length: totalCells }, (_, index) =>
      new Date(year, monthIndex, index - offset + 1)
    );
  }, [month]);

  function changeMonth(amount: number) {
    setMonth((current) =>
      new Date(
        current.getFullYear(),
        current.getMonth() + amount,
        1
      )
    );
    setSelectedDate(null);
  }

  function goToToday() {
    const today = new Date();
    setMonth(new Date(today.getFullYear(), today.getMonth(), 1));
    setSelectedDate(today);
  }

  function selectDay(date: Date) {
    setSelectedDate(date);

    if (date.getMonth() !== month.getMonth() ||
        date.getFullYear() !== month.getFullYear()) {
      setMonth(new Date(date.getFullYear(), date.getMonth(), 1));
    }
  }

  return (
    <main className="min-h-dvh bg-black px-4 py-8 text-white md:px-10">
      <div className="mx-auto max-w-6xl">

        <Link
          href="/dashboard"
          className="text-sm text-neutral-400 hover:text-white"
        >
          ← Dashboard
        </Link>

        <header className="mt-6 flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-xs uppercase tracking-[0.2em] text-neutral-500">
              NoizyCologne / Kalender
            </p>
            <h1 className="mt-2 text-3xl font-bold">
              Veranstaltungskalender
            </h1>
          </div>

          <button
            type="button"
            onClick={goToToday}
            className="rounded-lg border border-white/20 px-4 py-2 text-sm hover:bg-white/10"
          >
            Heute
          </button>
        </header>

        <section className="mt-8 rounded-2xl border border-white/10 bg-neutral-950 p-3 sm:p-6">

          {/* Monatsnavigation */}
          <div className="mb-6 flex items-center justify-between gap-3">
            <h2 className="text-xl font-semibold capitalize sm:text-2xl">
              {month.toLocaleDateString("de-DE", {
                month: "long",
                year: "numeric",
              })}
            </h2>

            <div className="flex gap-2">
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

          {/* Wochentage */}
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

          {/* Kalenderraster */}
          <div className="grid grid-cols-7 gap-px overflow-hidden rounded-xl border border-white/10 bg-white/10">
            {days.map((date) => {
              const inMonth = date.getMonth() === month.getMonth();
              const isWeekend = [5, 6].includes(date.getDay());
              const isSelected =
                selectedDate !== null && sameDay(date, selectedDate);

              const key = [
                date.getFullYear(),
                date.getMonth(),
                date.getDate(),
              ].join("-");

              return (
                <button
                  key={key}
                  type="button"
                  onClick={() => selectDay(date)}
                  aria-pressed={isSelected}
                  aria-label={date.toLocaleDateString("de-DE", {
                    weekday: "long",
                    day: "numeric",
                    month: "long",
                    year: "numeric",
                  })}
                  className={[
                    "min-h-16 p-2 text-left transition-colors sm:min-h-24 sm:p-3",
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
                  <span className="text-sm font-medium">
                    {date.getDate()}
                  </span>
                </button>
              );
            })}
          </div>
        </section>


{/* Editor für ausgewählten Tag */}
{selectedDate ? (
  <EntryEditor
    key={`${selectedDate.getFullYear()}-${selectedDate.getMonth()}-${selectedDate.getDate()}`}
    date={selectedDate}
  />
) : (
  <section className="mt-6 rounded-xl border border-white/10 bg-neutral-950 p-6">
    <p className="text-sm text-neutral-400">
      Wähle einen Tag aus, um einen Kalendereintrag anzulegen.
    </p>
  </section>
)}


      </div>
    </main>
  );
}
