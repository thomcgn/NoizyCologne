
"use client";

import { useState } from "react";
import { locations, partySeries } from "./catalog";

type EntryType = "event" | "closure";

interface EntryEditorProps {
  date: Date;
}

const inputStyle =
  "w-full rounded-lg border border-white/15 bg-neutral-900 " +
  "px-3 py-2.5 text-sm text-white outline-none " +
  "focus:border-white/60";

export default function EntryEditor({ date }: EntryEditorProps) {
  // Eintragstyp
  const [type, setType] = useState<EntryType>("event");

  // Stammdaten-Auswahl
  const [locationId, setLocationId] = useState("");
  const [partyId, setPartyId] = useState("");

  // Veranstaltung
  const [startTime, setStartTime] = useState("");

  // Location-Sperre
  const [reason, setReason] = useState("Geschlossen");

  // Gemeinsame Felder
  const [notes, setNotes] = useState("");

  // Ausgewählte Stammdaten
  const selectedLocation = locations.find(
    (item) => item.id === locationId
  );

  const selectedParty = partySeries.find(
    (item) => item.id === partyId
  );

  // Datum formatieren
  const formattedDate = date.toLocaleDateString("de-DE", {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
  });

  return (
    <section className="mt-6 rounded-xl border border-white/10 bg-neutral-950 p-6">
      {/* Überschrift */}
      <div className="mb-6">
        <h2 className="text-xl font-semibold">
          Neuer Kalendereintrag
        </h2>

        <p className="mt-1 text-sm text-neutral-400">
          {formattedDate}
        </p>
      </div>

      {/* Eintragstyp */}
      <div className="mb-6 grid grid-cols-2 gap-2">
        <button
          type="button"
          onClick={() => setType("event")}
          aria-pressed={type === "event"}
          className={`rounded-lg px-4 py-3 text-sm ${
            type === "event"
              ? "bg-white font-semibold text-black"
              : "border border-white/15 text-neutral-300"
          }`}
        >
          Veranstaltung
        </button>

        <button
          type="button"
          onClick={() => setType("closure")}
          aria-pressed={type === "closure"}
          className={`rounded-lg px-4 py-3 text-sm ${
            type === "closure"
              ? "bg-white font-semibold text-black"
              : "border border-white/15 text-neutral-300"
          }`}
        >
          Location-Sperre
        </button>
      </div>

      {/* Eingabefelder */}
      <div className="grid gap-5 sm:grid-cols-2">
        {/* Location */}
        <label className="block">
          <span className="mb-2 block text-sm text-neutral-300">
            Location
          </span>

          <select
            className={inputStyle}
            value={locationId}
            onChange={(event) => setLocationId(event.target.value)}
          >
            <option value="">Location auswählen</option>

            {locations.map((location) => (
              <option key={location.id} value={location.id}>
                {location.name}
              </option>
            ))}
          </select>
        </label>

        {type === "event" ? (
          <>
            {/* Partyreihe */}
            <label className="block">
              <span className="mb-2 block text-sm text-neutral-300">
                Partyreihe
              </span>

              <select
                className={inputStyle}
                value={partyId}
                onChange={(event) => setPartyId(event.target.value)}
              >
                <option value="">Partyreihe auswählen</option>

                {partySeries.map((party) => (
                  <option key={party.id} value={party.id}>
                    {party.name}
                  </option>
                ))}
              </select>
            </label>

            {/* Uhrzeit */}
            <label className="block">
              <span className="mb-2 block text-sm text-neutral-300">
                Beginn (optional)
              </span>

              <input
                className={inputStyle}
                type="time"
                value={startTime}
                onChange={(event) =>
                  setStartTime(event.target.value)
                }
              />
            </label>
          </>
        ) : (
          <>
            {/* Sperrgrund */}
            <label className="block">
              <span className="mb-2 block text-sm text-neutral-300">
                Grund
              </span>

              <select
                className={inputStyle}
                value={reason}
                onChange={(event) =>
                  setReason(event.target.value)
                }
              >
                <option value="Geschlossen">
                  Geschlossen
                </option>
                <option value="Betriebsferien">
                  Betriebsferien
                </option>
                <option value="Privatveranstaltung">
                  Privatveranstaltung
                </option>
                <option value="Sonstige Sperre">
                  Sonstige Sperre
                </option>
              </select>
            </label>
          </>
        )}
      </div>

      {/* Bemerkungen */}
      <label className="mt-5 block">
        <span className="mb-2 block text-sm text-neutral-300">
          Bemerkungen (optional)
        </span>

        <textarea
          className={inputStyle}
          value={notes}
          onChange={(event) => setNotes(event.target.value)}
          placeholder="Zusätzliche Informationen"
          rows={3}
        />
      </label>

      {/* Live-Vorschau */}
      <div className="mt-6 rounded-lg border border-white/10 bg-black p-4">
        <p className="mb-3 text-xs uppercase tracking-widest text-neutral-500">
          Vorschau
        </p>

        {/* Partyname oder Sperrgrund */}
        <p className="font-semibold">
          {type === "event"
            ? selectedParty?.name || "Neue Veranstaltung"
            : reason}
        </p>

        {/* Location */}
        <p className="mt-1 text-sm text-neutral-400">
          {selectedLocation?.name ||
            "Location noch nicht ausgewählt"}
        </p>

        {/* Instagram der Location */}
        {selectedLocation?.instagram && (
          <p className="mt-1 text-sm text-neutral-500">
            @{selectedLocation.instagram}
          </p>
        )}

        {/* Datum und Uhrzeit */}
        <p className="mt-3 text-sm text-neutral-400">
          {formattedDate}
          {type === "event" && startTime
            ? ` · ${startTime} Uhr`
            : ""}
        </p>

        {/* Bemerkungen */}
        {notes && (
          <p className="mt-3 whitespace-pre-wrap text-sm text-neutral-400">
            {notes}
          </p>
        )}
      </div>

      {/* Hinweis */}
      <p className="mt-4 text-xs text-neutral-500">
        Entwurfsansicht – noch keine Speicherung.
      </p>
    </section>
  );
}
