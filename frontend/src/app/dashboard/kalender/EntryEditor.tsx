"use client";

import { useState } from "react";
import { useCatalog } from "../CatalogProvider";

type EntryType = "event" | "closure";

interface EntryEditorProps {
  date: Date;
}

const inputStyle =
  "w-full rounded-lg border border-white/15 bg-neutral-900 " +
  "px-3 py-2.5 text-sm text-white outline-none " +
  "focus:border-white/60";

export default function EntryEditor({ date }: EntryEditorProps) {
  const { venues, parties, actors } = useCatalog();

  const [type, setType] = useState<EntryType>("event");
  const [locationId, setLocationId] = useState("");
  const [partyId, setPartyId] = useState("");
  const [startTime, setStartTime] = useState("");
  const [reason, setReason] = useState("Geschlossen");
  const [notes, setNotes] = useState("");

  // Erst wenn Checkboxen manuell geändert werden, überschreiben sie
  // die Voreinstellung der aktuell ausgewählten Partyreihe.
  const [mentionOverride, setMentionOverride] = useState<{
    partyId: string;
    djIds: string[];
  } | null>(null);

  const selectedLocation = venues.find((venue) => venue.id === locationId);
  const selectedParty = parties.find((party) => party.id === partyId);

  const availableDjs = actors.filter(
    (actor) =>
      selectedParty?.residentDjIds.includes(actor.id) ||
      selectedParty?.otherDjIds.includes(actor.id)
  );

  // Residents werden nur bei entsprechend konfigurierten Partyreihen
  // vorausgewählt. Ohne Instagram-Handle ist keine Mention möglich.
  const defaultMentionDjIds =
    selectedParty?.djMentionDefault === "residents"
      ? selectedParty.residentDjIds.filter((id) =>
          availableDjs.some((dj) => dj.id === id && dj.instagram)
        )
      : [];

  const selectedMentionDjIds =
    mentionOverride?.partyId === partyId
      ? mentionOverride.djIds
      : defaultMentionDjIds;

  const selectedDjMentions = availableDjs
    .filter((dj) => selectedMentionDjIds.includes(dj.id) && dj.instagram)
    .map((dj) => `@${dj.instagram}`);

  function toggleDjMention(djId: string, checked: boolean) {
    const nextIds = checked
      ? [...new Set([...selectedMentionDjIds, djId])]
      : selectedMentionDjIds.filter((id) => id !== djId);

    setMentionOverride({ partyId, djIds: nextIds });
  }

  const formattedDate = date.toLocaleDateString("de-DE", {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
  });

  return (
    <section className="mt-6 rounded-xl border border-white/10 bg-neutral-950 p-6">
      <div className="mb-6">
        <h2 className="text-xl font-semibold">Neuer Kalendereintrag</h2>
        <p className="mt-1 text-sm text-neutral-400">{formattedDate}</p>
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

      {/* Gemeinsame Eingaben und veranstaltungsspezifische Felder */}
      <div className="grid gap-5 sm:grid-cols-2">
        <label className="block">
          <span className="mb-2 block text-sm text-neutral-300">Location</span>
          <select
            className={inputStyle}
            value={locationId}
            onChange={(event) => setLocationId(event.target.value)}
          >
            <option value="">Location auswählen</option>
            {venues.map((venue) => (
              <option key={venue.id} value={venue.id}>
                {venue.name}
              </option>
            ))}
          </select>
        </label>

        {type === "event" ? (
          <>
            <label className="block">
              <span className="mb-2 block text-sm text-neutral-300">
                Partyreihe
              </span>
              <select
                className={inputStyle}
                value={partyId}
                onChange={(event) => {
                  setPartyId(event.target.value);
                  setMentionOverride(null);
                }}
              >
                <option value="">Partyreihe auswählen</option>
                {parties.map((party) => (
                  <option key={party.id} value={party.id}>
                    {party.name}
                  </option>
                ))}
              </select>
            </label>

            <label className="block">
              <span className="mb-2 block text-sm text-neutral-300">
                Beginn (optional)
              </span>
              <input
                className={inputStyle}
                type="time"
                value={startTime}
                onChange={(event) => setStartTime(event.target.value)}
              />
            </label>
          </>
        ) : (
          <label className="block">
            <span className="mb-2 block text-sm text-neutral-300">Grund</span>
            <select
              className={inputStyle}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            >
              <option value="Geschlossen">Geschlossen</option>
              <option value="Betriebsferien">Betriebsferien</option>
              <option value="Privatveranstaltung">Privatveranstaltung</option>
              <option value="Sonstige Sperre">Sonstige Sperre</option>
            </select>
          </label>
        )}
      </div>

      {/* Mentions – unabhängig von der tatsächlichen DJ-Besetzung */}
      {type === "event" && selectedParty && (
        <fieldset className="mt-6 rounded-lg border border-white/10 p-4">
          <legend className="px-2 font-semibold">Instagram-Mentions · DJs</legend>
          <p className="mb-4 text-sm text-neutral-400">
            Wähle die DJs aus, die im Beitrag erwähnt werden sollen.
          </p>

          {availableDjs.length === 0 ? (
            <p className="text-sm text-neutral-500">
              Keine DJs mit dieser Partyreihe verknüpft.
            </p>
          ) : (
            <div className="space-y-3">
              {availableDjs.map((dj) => (
                <label
                  key={dj.id}
                  className="flex items-center gap-3 rounded-lg bg-neutral-900 p-3"
                >
                  <input
                    type="checkbox"
                    className="size-4 accent-white"
                    checked={selectedMentionDjIds.includes(dj.id)}
                    disabled={!dj.instagram}
                    onChange={(event) =>
                      toggleDjMention(dj.id, event.target.checked)
                    }
                  />
                  <span className="text-sm">
                    <span className="block font-medium">{dj.name}</span>
                    <span className="text-neutral-500">
                      {dj.instagram
                        ? `@${dj.instagram}`
                        : "Kein Instagram-Handle hinterlegt"}
                    </span>
                  </span>
                </label>
              ))}
            </div>
          )}
        </fieldset>
      )}

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
        <p className="font-semibold">
          {type === "event"
            ? selectedParty?.name || "Neue Veranstaltung"
            : reason}
        </p>
        <p className="mt-1 text-sm text-neutral-400">
          {selectedLocation?.name || "Location noch nicht ausgewählt"}
        </p>
        {selectedLocation?.instagram && (
          <p className="mt-1 text-sm text-neutral-500">
            @{selectedLocation.instagram}
          </p>
        )}
        {type === "event" && selectedDjMentions.length > 0 && (
          <p className="mt-2 text-sm text-neutral-300">
            DJs: {selectedDjMentions.join(", ")}
          </p>
        )}
        <p className="mt-3 text-sm text-neutral-400">
          {formattedDate}
          {type === "event" && startTime ? ` · ${startTime} Uhr` : ""}
        </p>
        {notes && (
          <p className="mt-3 whitespace-pre-wrap text-sm text-neutral-400">
            {notes}
          </p>
        )}
      </div>

      <p className="mt-4 text-xs text-neutral-500">
        Entwurfsansicht – noch keine Speicherung.
      </p>
    </section>
  );
}
