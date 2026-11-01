"use client";

import Link from "next/link";
import Image from "next/image";
import { useMemo, useState } from "react";
import {
  useCatalog,
  type Actor,
  type CalendarEntry,
  type PartySeries,
} from "../CatalogProvider";

function formatDate(date: string) {
  return new Intl.DateTimeFormat("de-DE", {
    weekday: "long",
    day: "2-digit",
    month: "long",
    year: "numeric",
  }).format(new Date(`${date}T12:00:00`));
}

function displayHandle(actor: Actor) {
  return actor.instagram ? `@${actor.instagram.replace(/^@/, "")}` : "Kein Handle";
}

function buildCaption(entry: CalendarEntry, party: PartySeries, locationName: string) {
  if (entry.type !== "event") return "";
  return [
    party.name,
    formatDate(entry.date),
    entry.startTime ? `Beginn: ${entry.startTime} Uhr` : null,
    locationName,
    entry.notes || null,
  ]
    .filter((line): line is string => Boolean(line))
    .join("\n");
}

type DraftState = {
  caption: string;
  hashtags: string;
  mentionIds: string[];
};

type GateState = {
  captionReviewed: boolean;
  hashtagsReviewed: boolean;
  mentionsReviewed: boolean;
  feedOrderReviewed: boolean;
};

export default function RedaktionPage() {
  const { entries, entriesLoading, entriesError, reloadEntries, parties, venues, actors } = useCatalog();
  const eventEntries = useMemo(
    () => entries
      .filter((entry): entry is Extract<CalendarEntry, { type: "event" }> => entry.type === "event")
      .sort((a, b) => a.date.localeCompare(b.date) || a.id.localeCompare(b.id)),
    [entries]
  );
  const [selectedId, setSelectedId] = useState("");
  const [drafts, setDrafts] = useState<Record<string, DraftState>>({});
  const [gateStates, setGateStates] = useState<Record<string, GateState>>({});
  const [feedOrder, setFeedOrder] = useState<string[]>([]);
  const orderedEventEntries = useMemo(() => {
    const byId = new Map(eventEntries.map((entry) => [entry.id, entry]));
    const knownIds = feedOrder.filter((id) => byId.has(id));
    const newIds = eventEntries.map((entry) => entry.id).filter((id) => !knownIds.includes(id));
    return [...knownIds, ...newIds]
      .map((id) => byId.get(id))
      .filter((entry): entry is Extract<CalendarEntry, { type: "event" }> => Boolean(entry));
  }, [eventEntries, feedOrder]);
  const activeId = selectedId && eventEntries.some((entry) => entry.id === selectedId)
    ? selectedId
    : orderedEventEntries[0]?.id ?? "";

  const selectedEntry = eventEntries.find((entry) => entry.id === activeId) ?? null;
  const selectedParty = selectedEntry
    ? parties.find((party) => party.id === selectedEntry.partyId) ?? null
    : null;
  const selectedVenue = selectedEntry
    ? venues.find((venue) => venue.id === selectedEntry.locationId) ?? null
    : null;
  const mentionableDjs = selectedParty
    ? actors.filter((actor) =>
        actor.roles.includes("dj") &&
        [...selectedParty.residentDjIds, ...selectedParty.otherDjIds].includes(actor.id) &&
        actor.instagram
      )
    : [];
  const flyerUrl = selectedParty?.defaultFlyer?.url ?? null;

  const defaultDraft: DraftState = selectedEntry && selectedParty
    ? {
        caption: buildCaption(selectedEntry, selectedParty, selectedVenue?.name ?? ""),
        hashtags: "",
        mentionIds: selectedEntry.mentionDjIds,
      }
    : { caption: "", hashtags: "", mentionIds: [] };
  const draft = drafts[activeId] ?? defaultDraft;
  const gates = gateStates[activeId] ?? {
    captionReviewed: false,
    hashtagsReviewed: false,
    mentionsReviewed: false,
    feedOrderReviewed: false,
  };
  const automaticGates = Boolean(selectedEntry && selectedParty && selectedVenue && flyerUrl);
  const manualGates = Object.values(gates).every(Boolean);
  const allGatesPassed = automaticGates && manualGates;

  function updateDraft(patch: Partial<DraftState>) {
    if (!activeId) return;
    setDrafts((current) => ({
      ...current,
      [activeId]: { ...draft, ...patch },
    }));
  }

  function toggleMention(id: string) {
    updateDraft({
      mentionIds: draft.mentionIds.includes(id)
        ? draft.mentionIds.filter((item) => item !== id)
        : [...draft.mentionIds, id],
    });
  }

  function toggleGate(gate: keyof GateState) {
    if (!activeId) return;
    setGateStates((current) => ({
      ...current,
      [activeId]: { ...gates, [gate]: !gates[gate] },
    }));
  }

  function moveEvent(entryId: string, direction: -1 | 1) {
    const currentOrder = orderedEventEntries.map((entry) => entry.id);
    const currentIndex = currentOrder.indexOf(entryId);
    const nextIndex = currentIndex + direction;
    if (currentIndex < 0 || nextIndex < 0 || nextIndex >= currentOrder.length) return;
    [currentOrder[currentIndex], currentOrder[nextIndex]] = [currentOrder[nextIndex], currentOrder[currentIndex]];
    setFeedOrder(currentOrder);
    setGateStates((current) => ({
      ...current,
      [entryId]: {
        ...(current[entryId] ?? {
          captionReviewed: false,
          hashtagsReviewed: false,
          mentionsReviewed: false,
          feedOrderReviewed: false,
        }),
        feedOrderReviewed: false,
      },
    }));
  }

  return (
    <main className="app-page min-h-dvh bg-black px-4 py-6 text-white sm:py-8 md:px-10">
      <div className="mx-auto max-w-7xl">
        <Link href="/dashboard" className="text-sm text-neutral-400 hover:text-white">
          ← Dashboard
        </Link>

        <header className="mt-6 border-b border-white/10 pb-8">
          <p className="text-xs uppercase tracking-[0.2em] text-neutral-500">
            NoizyCologne / Redaktion
          </p>
          <h1 className="mt-2 text-3xl font-bold tracking-tight sm:text-4xl">
            Instagram-Post vorbereiten
          </h1>
          <p className="mt-3 max-w-2xl text-sm leading-6 text-neutral-400">
            Vorschau und Entwurf für das Team. Diese Ansicht veröffentlicht nichts auf Instagram.
          </p>
        </header>

        {entriesLoading && (
          <p className="mt-6 text-sm text-neutral-400" role="status">
            Veranstaltungen werden aus PostgreSQL geladen ...
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

        {!entriesLoading && !entriesError && eventEntries.length === 0 && (
          <div className="mt-8 rounded-xl border border-dashed border-white/15 p-8 text-center">
            <p className="font-medium">Noch keine Veranstaltungen vorhanden.</p>
            <p className="mt-2 text-sm text-neutral-400">
              Lege zuerst einen Event im Kalender an. Location-Sperren erscheinen nicht in der Redaktion.
            </p>
            <Link
              href="/dashboard/kalender"
              className="mt-5 inline-flex min-h-11 items-center rounded-lg bg-white px-4 py-2 text-sm font-semibold text-black"
            >
              Zum Kalender
            </Link>
          </div>
        )}

        {eventEntries.length > 0 && (
          <div className="mt-8 grid gap-6 lg:grid-cols-[280px_minmax(0,1fr)_minmax(300px,420px)]">
            <section className="rounded-xl border border-white/10 bg-neutral-950 p-4">
              <h2 className="text-lg font-semibold">Veranstaltung</h2>
              <label className="mt-4 block">
                <span className="mb-2 block text-sm text-neutral-300">Entwurf auswählen</span>
                <select
                  className="min-h-12 w-full rounded-lg border border-white/15 bg-neutral-900 px-3 py-2.5 text-sm text-white outline-none focus:border-white/60"
                  value={selectedId}
                  onChange={(event) => setSelectedId(event.target.value)}
                >
                  {orderedEventEntries.map((entry) => {
                    const party = parties.find((item) => item.id === entry.partyId);
                    return (
                      <option key={entry.id} value={entry.id}>
                        {entry.date} · {party?.name ?? "Unbekannte Partyreihe"}
                      </option>
                    );
                  })}
                </select>
              </label>

              <div className="mt-6 border-t border-white/10 pt-5">
                <div className="flex items-center justify-between gap-3">
                  <h3 className="text-sm font-semibold">Feed-Reihenfolge</h3>
                  <span className="text-xs text-neutral-500">nur lokal</span>
                </div>
                <p className="mt-2 text-xs leading-5 text-neutral-500">
                  Die Reihenfolge wird nicht gespeichert und verändert keine Kalenderdaten.
                </p>
                <ol className="mt-3 space-y-2">
                  {orderedEventEntries.map((entry, index) => {
                    const party = parties.find((item) => item.id === entry.partyId);
                    const isActive = entry.id === activeId;
                    return (
                      <li
                        key={entry.id}
                        className={`rounded-lg border p-2 ${isActive ? "border-white/40 bg-white/10" : "border-white/10 bg-neutral-900"}`}
                      >
                        <div className="flex items-center gap-2">
                          <button
                            type="button"
                            className="min-h-10 min-w-10 rounded-md border border-white/15 text-sm text-neutral-300 disabled:cursor-not-allowed disabled:opacity-30"
                            onClick={() => moveEvent(entry.id, -1)}
                            disabled={index === 0}
                            aria-label={`${party?.name ?? "Veranstaltung"} nach oben verschieben`}
                          >
                            ↑
                          </button>
                          <button
                            type="button"
                            className="min-h-10 min-w-10 rounded-md border border-white/15 text-sm text-neutral-300 disabled:cursor-not-allowed disabled:opacity-30"
                            onClick={() => moveEvent(entry.id, 1)}
                            disabled={index === orderedEventEntries.length - 1}
                            aria-label={`${party?.name ?? "Veranstaltung"} nach unten verschieben`}
                          >
                            ↓
                          </button>
                          <button
                            type="button"
                            className="min-w-0 flex-1 text-left"
                            onClick={() => setSelectedId(entry.id)}
                          >
                            <span className="block text-xs text-neutral-500">{index + 1}. · {entry.date}</span>
                            <span className="block truncate text-sm">{party?.name ?? "Unbekannte Partyreihe"}</span>
                          </button>
                        </div>
                      </li>
                    );
                  })}
                </ol>
              </div>

              {selectedEntry && selectedParty && (
                <div className="mt-6 space-y-3 border-t border-white/10 pt-5 text-sm">
                  <p><span className="text-neutral-500">Partyreihe:</span> {selectedParty.name}</p>
                  <p><span className="text-neutral-500">Datum:</span> {formatDate(selectedEntry.date)}</p>
                  <p><span className="text-neutral-500">Location:</span> {selectedVenue?.name ?? "Unbekannte Location"}</p>
                  <p className="text-xs text-neutral-500">
                    Der Entwurf ist nicht automatisch zur Veröffentlichung freigegeben.
                  </p>
                </div>
              )}
            </section>

            <section className="rounded-xl border border-white/10 bg-neutral-950 p-4 sm:p-6">
              <div className="mb-5 flex items-center justify-between gap-3">
                <div>
                  <h2 className="text-lg font-semibold">Post-Vorschau</h2>
                  <p className="mt-1 text-xs text-neutral-500">Nur lokale Vorschau</p>
                </div>
                <span className="rounded-full border border-amber-400/30 px-3 py-1 text-xs text-amber-300">
                  Entwurf
                </span>
              </div>

              <article className="overflow-hidden rounded-xl border border-white/10 bg-black">
                <div className="flex items-center gap-3 border-b border-white/10 p-4">
                  <div className="flex size-9 items-center justify-center rounded-full bg-white text-xs font-black text-black">N</div>
                  <div>
                    <p className="text-sm font-semibold">noizycologne</p>
                    <p className="text-xs text-neutral-500">Team-Entwurf</p>
                  </div>
                </div>
                {flyerUrl ? (
                  <Image
                    src={flyerUrl}
                    alt={`Dauerflyer ${selectedParty?.name ?? "Partyreihe"}`}
                    width={900}
                    height={900}
                    unoptimized
                    className="aspect-square w-full object-cover"
                  />
                ) : (
                  <div className="flex aspect-square items-center justify-center bg-neutral-900 p-8 text-center text-sm text-neutral-500">
                    Kein Dauerflyer hinterlegt. Ein Event-Flyer kann später separat ergänzt werden.
                  </div>
                )}
                <div className="space-y-3 p-4">
                  <p className="text-sm font-semibold">noizycologne</p>
                  <p className="whitespace-pre-wrap text-sm leading-6 text-neutral-300">{draft.caption || "Caption hier vorbereiten ..."}</p>
                  {draft.hashtags.trim() && <p className="whitespace-pre-wrap text-sm text-blue-300">{draft.hashtags}</p>}
                  {draft.mentionIds.length > 0 && (
                    <p className="text-sm text-blue-300">
                      {draft.mentionIds
                        .map((id) => actors.find((actor) => actor.id === id))
                        .filter((actor): actor is Actor => Boolean(actor?.instagram))
                        .map((actor) => displayHandle(actor))
                        .join(" ")}
                    </p>
                  )}
                </div>
              </article>
            </section>

            <section className="rounded-xl border border-white/10 bg-neutral-950 p-4 sm:p-6">
              <h2 className="text-lg font-semibold">Entwurf bearbeiten</h2>
              <p className="mt-2 text-sm leading-6 text-neutral-400">
                Änderungen bleiben zunächst nur in dieser Ansicht erhalten.
              </p>
              <label className="mt-5 block">
                <span className="mb-2 block text-sm text-neutral-300">Caption</span>
                <textarea
                  className="min-h-44 w-full rounded-lg border border-white/15 bg-neutral-900 px-3 py-2.5 text-sm text-white outline-none focus:border-white/60"
                  value={draft.caption}
                  onChange={(event) => updateDraft({ caption: event.target.value })}
                />
              </label>
              <label className="mt-5 block">
                <span className="mb-2 block text-sm text-neutral-300">Hashtags</span>
                <textarea
                  className="min-h-24 w-full rounded-lg border border-white/15 bg-neutral-900 px-3 py-2.5 text-sm text-white outline-none focus:border-white/60"
                  placeholder="#noizycologne ..."
                  value={draft.hashtags}
                  onChange={(event) => updateDraft({ hashtags: event.target.value })}
                />
              </label>
              <fieldset className="mt-5">
                <legend className="text-sm text-neutral-300">DJ-Mentions</legend>
                <p className="mt-1 text-xs leading-5 text-neutral-500">
                  Mentions sind redaktionelle Vorschläge und kein bestätigtes Line-up.
                </p>
                <div className="mt-3 space-y-2">
                  {mentionableDjs.length === 0 ? (
                    <p className="text-sm text-neutral-500">Keine verknüpften DJs mit Instagram-Handle.</p>
                  ) : mentionableDjs.map((actor) => (
                    <label key={actor.id} className="flex min-h-11 items-center gap-3 rounded-lg bg-neutral-900 px-3 py-2">
                      <input
                        type="checkbox"
                        className="size-4 accent-white"
                        checked={draft.mentionIds.includes(actor.id)}
                        onChange={() => toggleMention(actor.id)}
                      />
                      <span className="text-sm">{actor.name} <span className="text-neutral-500">{displayHandle(actor)}</span></span>
                    </label>
                  ))}
                </div>
              </fieldset>
              <fieldset className="mt-6 rounded-lg border border-white/10 p-4">
                <legend className="px-2 text-sm font-semibold">Pre-Publishing-Gates</legend>
                <p className="mb-3 text-xs leading-5 text-neutral-500">
                  Diese Prüfung ist lokal. Sie veröffentlicht nichts und ersetzt noch keine serverseitige Freigabe.
                </p>
                <div className="space-y-2 text-sm">
                  <label className="flex items-start gap-3">
                    <input type="checkbox" className="mt-1 size-4 accent-white" checked={Boolean(flyerUrl)} disabled readOnly />
                    <span className={flyerUrl ? "text-neutral-200" : "text-amber-300"}>
                      Bild vorhanden{flyerUrl ? "" : " – Dauerflyer fehlt"}
                    </span>
                  </label>
                  <label className="flex items-start gap-3">
                    <input type="checkbox" className="mt-1 size-4 accent-white" checked={Boolean(selectedEntry && selectedParty && selectedVenue)} disabled readOnly />
                    <span className={selectedEntry && selectedParty && selectedVenue ? "text-neutral-200" : "text-amber-300"}>
                      Datum, Location und Partyreihe geprüft
                    </span>
                  </label>
                  {([
                    ["captionReviewed", "Caption geprüft"],
                    ["hashtagsReviewed", "Hashtags geprüft"],
                    ["mentionsReviewed", "Mentions geprüft"],
                    ["feedOrderReviewed", "Feed-Reihenfolge geprüft"],
                  ] as const).map(([gate, label]) => (
                    <label key={gate} className="flex min-h-11 items-start gap-3">
                      <input
                        type="checkbox"
                        className="mt-1 size-4 accent-white"
                        checked={gates[gate]}
                        onChange={() => toggleGate(gate)}
                      />
                      <span>{label}</span>
                    </label>
                  ))}
                </div>
                <div className="mt-4 border-t border-white/10 pt-4">
                  <button
                    type="button"
                    disabled
                    className="min-h-11 w-full rounded-lg bg-white px-4 py-2 text-sm font-semibold text-black disabled:cursor-not-allowed disabled:opacity-50"
                    title="Serverseitige Freigabe ist noch nicht implementiert"
                  >
                    {allGatesPassed ? "Freigabe technisch vorbereitet" : "Freigabe noch blockiert"}
                  </button>
                  <p className="mt-2 text-xs text-neutral-500">
                    {allGatesPassed
                      ? "Alle lokalen Checks sind erfüllt. Eine serverseitige Freigabe ist noch erforderlich."
                      : "Alle Checks müssen erfüllt sein; ohne Bild bleibt die Freigabe blockiert."}
                  </p>
                </div>
              </fieldset>
            </section>
          </div>
        )}
      </div>
    </main>
  );
}
