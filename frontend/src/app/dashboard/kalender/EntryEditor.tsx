"use client";

import { useState, type FormEvent } from "react";
import {
  toDateKey,
  useCatalog,
  type CalendarEntry,
  type CalendarEntryPayload,
} from "../CatalogProvider";

type EntryType = "event" | "closure";

interface EntryEditorProps {
  date: Date;
  entry?: CalendarEntry | null;
  onSaved: () => void;
  onCancel: () => void;
}

const inputStyle =
  "w-full rounded-lg border border-white/15 bg-neutral-900 " +
  "px-3 py-2.5 text-sm text-white outline-none " +
  "focus:border-white/60";

export default function EntryEditor({
  date,
  entry = null,
  onSaved,
  onCancel,
}: EntryEditorProps) {
  const {
    venues,
    parties,
    actors,
    createEntry,
    updateEntry,
  } = useCatalog();

  const [type, setType] = useState<EntryType>(entry?.type ?? "event");
  const [locationId, setLocationId] = useState(entry?.locationId ?? "");
  const [partyId, setPartyId] = useState(
    entry?.type === "event" ? entry.partyId : ""
  );
  const [startTime, setStartTime] = useState(
    entry?.type === "event" ? entry.startTime : ""
  );
  const [reason, setReason] = useState(
    entry?.type === "closure" ? entry.reason : "Geschlossen"
  );
  const [notes, setNotes] = useState(entry?.notes ?? "");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  // Beim Bearbeiten die bereits gespeicherte Auswahl behalten.
  // Bei neuen Einträgen gelten die Resident-Vorgaben der Partyreihe.
  const [mentionOverride, setMentionOverride] = useState<{
    partyId: string;
    djIds: string[];
  } | null>(
    entry?.type === "event"
      ? { partyId: entry.partyId, djIds: [...entry.mentionDjIds] }
      : null
  );

  const selectedLocation = venues.find((venue) => venue.id === locationId);
  const selectedParty = parties.find((party) => party.id === partyId);

  const availableDjs = actors.filter(
    (actor) =>
      selectedParty?.residentDjIds.includes(actor.id) ||
      selectedParty?.otherDjIds.includes(actor.id)
  );

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
    .map((dj) => `@${dj.instagram.replace(/^@/, "")}`);

  function toggleDjMention(djId: string, checked: boolean) {
    const nextIds = checked
      ? [...new Set([...selectedMentionDjIds, djId])]
      : selectedMentionDjIds.filter((id) => id !== djId);
    setMentionOverride({ partyId, djIds: nextIds });
  }

  async function saveEntry(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");

    if (!locationId || !venues.some((venue) => venue.id === locationId)) {
      setError("Bitte eine Location auswählen.");
      return;
    }

    if (type === "event" && (!partyId || !selectedParty)) {
      setError("Bitte eine Partyreihe auswählen.");
      return;
    }

    const base = {
      date: toDateKey(date),
      locationId,
      notes: notes.trim(),
    };

    let updated: CalendarEntryPayload;
    if (type === "event") {
      updated = {
        ...base,
        type: "event",
        partyId,
        startTime,
        mentionDjIds: availableDjs
          .filter((dj) => dj.instagram && selectedMentionDjIds.includes(dj.id))
          .map((dj) => dj.id),
      };
    } else {
      updated = {
        ...base,
        type: "closure",
        reason,
      };
    }

    setSaving(true);
    try {
      if (entry) {
        await updateEntry({ id: entry.id, ...updated });
      } else {
        await createEntry(updated);
      }
      onSaved();
    } catch (cause) {
      setError(
        cause instanceof Error
          ? `Kalendereintrag konnte nicht gespeichert werden: ${cause.message}`
          : "Kalendereintrag konnte wegen eines unbekannten Netzwerkfehlers nicht gespeichert werden."
      );
    } finally {
      setSaving(false);
    }
  }

  const formattedDate = date.toLocaleDateString("de-DE", {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
  });

  return (
    <form
      onSubmit={saveEntry}
      className="mt-6 rounded-xl border border-white/10 bg-neutral-950 p-6"
    >
      <div className="mb-6">
        <h2 className="text-xl font-semibold">
          {entry ? "Kalendereintrag bearbeiten" : "Neuer Kalendereintrag"}
        </h2>
        <p className="mt-1 text-sm text-neutral-400">{formattedDate}</p>
      </div>

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
                        ? `@${dj.instagram.replace(/^@/, "")}`
                        : "Kein Instagram-Handle hinterlegt"}
                    </span>
                  </span>
                </label>
              ))}
            </div>
          )}
        </fieldset>
      )}

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

      <div
        className="mt-6 rounded-lg border border-white/10 bg-black p-4"
        style={
          type === "event" && selectedParty
            ? { borderLeftColor: selectedParty.color, borderLeftWidth: "5px" }
            : undefined
        }
      >
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

      {error && (
        <p role="alert" className="mt-4 text-sm text-red-400">
          {error}
        </p>
      )}

      <div className="mt-6 flex flex-wrap gap-3">
        <button
          type="submit"
          disabled={saving}
          className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black"
        >
          {saving
            ? "Speichern ..."
            : entry
              ? "Änderungen übernehmen"
              : "Eintrag speichern"}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="rounded-lg border border-white/15 px-5 py-3 text-sm hover:bg-white/10"
        >
          Abbrechen
        </button>
      </div>
    </form>
  );
}
