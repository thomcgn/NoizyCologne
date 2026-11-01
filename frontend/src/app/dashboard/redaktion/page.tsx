"use client";

import Link from "next/link";
import Image from "next/image";
import { useEffect, useMemo, useRef, useState, type PointerEvent } from "react";
import {
  useCatalog,
  type Actor,
  type CalendarEntry,
  type CatalogItem,
  type EditorialDraftStatus,
  type GenreSuggestion,
} from "../CatalogProvider";
import {
  CAPTION_PRETEXT_STORAGE_KEY,
  DEFAULT_CAPTION_PRETEXT,
} from "../captionSettings";

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

function formatCaptionDate(date: string) {
  const [year, month, day] = date.split("-");
  const weekday = new Intl.DateTimeFormat("de-DE", {
    weekday: "long",
    timeZone: "UTC",
  }).format(new Date(`${date}T00:00:00Z`));
  return `${weekday} ${day}.${month}.${year.slice(-2)}`;
}

function buildCaption(
  entry: CalendarEntry,
  venue: CatalogItem,
  actors: Actor[],
  preText: string,
  genres: string[] = [],
  mentionIds: string[] = entry.type === "event" ? entry.mentionDjIds : [],
) {
  if (entry.type !== "event") return "";
  const djHandles = mentionIds
    .map((id) => actors.find((actor) => actor.id === id)?.instagram)
    .filter((handle): handle is string => Boolean(handle?.trim()))
    .map((handle) => `@${handle.replace(/^@/, "")}`);
  const locationHandle = venue.instagram.trim()
    ? `@${venue.instagram.replace(/^@/, "")}`
    : null;
  return [
    preText.trim() || DEFAULT_CAPTION_PRETEXT,
    formatCaptionDate(entry.date),
    genres.length > 0 ? genres.join(", ") : null,
    djHandles.length > 0 ? `mit ${djHandles.join(" & ")}` : null,
    locationHandle ? `im ${locationHandle}` : null,
  ]
    .filter((line): line is string => Boolean(line))
    .join(" ");
}

function isLegacyCaption(caption: string, entry: CalendarEntry) {
  if (entry.type !== "event") return false;
  return caption.includes("\n") || caption.includes("\r") || caption.includes("Beginn:");
}

type DraftState = {
  caption: string;
  hashtags: string;
  mentionIds: string[];
  genres: string[];
  status: EditorialDraftStatus;
};

type ActiveGenre = {
  id: string;
  canonicalName: string;
  displayName: string;
  active: boolean;
};

type GateState = {
  captionReviewed: boolean;
  hashtagsReviewed: boolean;
  mentionsReviewed: boolean;
  feedOrderReviewed: boolean;
};

export default function RedaktionPage() {
  const {
    entries,
    entriesLoading,
    entriesError,
    reloadEntries,
    parties,
    venues,
    actors,
    drafts: persistedDrafts,
    draftsLoading,
    draftsError,
    reloadDrafts,
    createDraft,
    updateDraft: updatePersistedDraft,
  } = useCatalog();
  const { uploadEventFlyer, deleteEventFlyer } = useCatalog();
  const { analyzeEventFlyerGenres } = useCatalog();
  const eventEntries = useMemo(
    () => entries
      .filter((entry): entry is Extract<CalendarEntry, { type: "event" }> => entry.type === "event")
      // Instagram zeigt im Profilraster den zuletzt veröffentlichten Beitrag zuerst.
      // Diese lokale Standardreihenfolge bildet die spätere Profilansicht ab:
      // neuestes Datum oben links, danach zeilenweise von links nach rechts.
      .sort((a, b) => b.date.localeCompare(a.date) || b.id.localeCompare(a.id)),
    [entries]
  );
  const [selectedId, setSelectedId] = useState("");
  const [localDrafts, setLocalDrafts] = useState<Record<string, DraftState>>({});
  const [gateStates, setGateStates] = useState<Record<string, GateState>>({});
  const [eventFlyerSaving, setEventFlyerSaving] = useState(false);
  const [eventFlyerError, setEventFlyerError] = useState("");
  const [genreAnalysis, setGenreAnalysis] = useState<Record<string, GenreSuggestion[]>>({});
  const [genreAnalysisLoading, setGenreAnalysisLoading] = useState(false);
  const [genreAnalysisError, setGenreAnalysisError] = useState("");
  const [draftSaving, setDraftSaving] = useState(false);
  const [draftError, setDraftError] = useState("");
  const [availableGenres, setAvailableGenres] = useState<ActiveGenre[]>([]);
  const [genresLoading, setGenresLoading] = useState(true);
  const [genresError, setGenresError] = useState("");
  const [captionPreText] = useState(() => {
    if (typeof window === "undefined") return DEFAULT_CAPTION_PRETEXT;
    return window.localStorage.getItem(CAPTION_PRETEXT_STORAGE_KEY)?.trim() || DEFAULT_CAPTION_PRETEXT;
  });
  const [feedOrder, setFeedOrder] = useState<string[]>([]);
  const [draggedId, setDraggedId] = useState<string | null>(null);
  const [dropTargetId, setDropTargetId] = useState<string | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const dragStartRef = useRef<{ id: string; x: number; y: number; pointerId: number } | null>(null);
  const dragMovedRef = useRef(false);
  useEffect(() => {
    let cancelled = false;
    async function loadGenres() {
      setGenresLoading(true);
      setGenresError("");
      try {
        const response = await fetch("/api/v1/genres", { credentials: "same-origin" });
        if (!response.ok) throw new Error(`Die Genreverwaltung antwortete mit HTTP ${response.status}.`);
        const body: unknown = await response.json();
        if (!Array.isArray(body)) throw new Error("Die Genreverwaltung lieferte kein gültiges Listenformat.");
        const genres = body.filter((item): item is ActiveGenre => {
          if (typeof item !== "object" || item === null) return false;
          const genre = item as Record<string, unknown>;
          return typeof genre.id === "string"
            && typeof genre.canonicalName === "string"
            && typeof genre.displayName === "string"
            && genre.active === true;
        });
        if (!cancelled) setAvailableGenres(genres);
      } catch (cause) {
        if (!cancelled) setGenresError(cause instanceof Error ? cause.message : "Genres konnten nicht geladen werden.");
      } finally {
        if (!cancelled) setGenresLoading(false);
      }
    }
    void loadGenres();
    return () => { cancelled = true; };
  }, []);
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
    : orderedEventEntries[0]?.id ?? eventEntries[0]?.id ?? "";

  const selectedEntry = eventEntries.find((entry) => entry.id === activeId) ?? null;
  const selectedParty = selectedEntry
    ? parties.find((party) => party.id === selectedEntry.partyId) ?? null
    : null;
  const selectedVenue = selectedEntry
    ? venues.find((venue) => venue.id === selectedEntry.locationId) ?? null
    : null;
  const persistedDraft = selectedEntry
    ? persistedDrafts.find((item) => item.calendarEntryId === selectedEntry.id) ?? null
    : null;
  const mentionableDjs = selectedParty
    ? actors.filter((actor) =>
        actor.roles.includes("dj") &&
        [...selectedParty.residentDjIds, ...selectedParty.otherDjIds].includes(actor.id) &&
        actor.instagram
      )
    : [];
  const partyMentionDefaults = selectedParty?.djMentionDefault === "residents"
    ? selectedParty.residentDjIds
    : [];
  const configuredMentionIds = selectedEntry && selectedEntry.mentionDjIds.length > 0
    ? selectedEntry.mentionDjIds
    : partyMentionDefaults;
  const initialMentionIds = persistedDraft && persistedDraft.mentionActorIds.length > 0
    ? persistedDraft.mentionActorIds
    : configuredMentionIds;
  const eventFlyer = selectedEntry?.eventFlyer ?? null;
  const flyerUrl = eventFlyer?.url ?? selectedParty?.defaultFlyer?.url ?? null;
  const currentGenreSuggestions = selectedEntry ? genreAnalysis[selectedEntry.id] ?? [] : [];
  const generatedCaption = selectedEntry && selectedVenue
    ? buildCaption(
        selectedEntry,
        selectedVenue,
        actors,
        captionPreText,
        persistedDraft?.genres ?? [],
        initialMentionIds,
      )
    : "";

  const defaultDraft: DraftState = selectedEntry && selectedParty
    ? {
        caption: persistedDraft
          && !isLegacyCaption(persistedDraft.caption, selectedEntry)
          ? persistedDraft.caption
          : generatedCaption,
        hashtags: persistedDraft?.hashtags ?? "",
        mentionIds: initialMentionIds,
        genres: persistedDraft?.genres ?? [],
        status: persistedDraft?.status ?? "DRAFT",
      }
    : { caption: "", hashtags: "", mentionIds: [], genres: [], status: "DRAFT" };
  const draft = localDrafts[activeId] ?? defaultDraft;
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
    setLocalDrafts((current) => ({
      ...current,
      [activeId]: { ...draft, ...patch },
    }));
  }

  function toggleGenre(genre: string) {
    const genres = draft.genres.includes(genre)
      ? draft.genres.filter((item) => item !== genre)
      : [...draft.genres, genre];
    updateDraft({
      genres,
      caption: selectedEntry && selectedParty && selectedVenue
        ? buildCaption(selectedEntry, selectedVenue, actors, captionPreText, genres, draft.mentionIds)
        : draft.caption,
    });
  }

  function applyGenreSuggestions() {
    const genres = currentGenreSuggestions.map((suggestion) => suggestion.genre);
    updateDraft({
      genres,
      caption: selectedEntry && selectedParty && selectedVenue
        ? buildCaption(selectedEntry, selectedVenue, actors, captionPreText, genres, draft.mentionIds)
        : draft.caption,
    });
  }

  async function analyzeGenres() {
    if (!selectedEntry || selectedEntry.type !== "event" || !eventFlyer) return;
    setGenreAnalysisLoading(true);
    setGenreAnalysisError("");
    try {
      const suggestions = await analyzeEventFlyerGenres(selectedEntry.id);
      setGenreAnalysis((current) => ({ ...current, [selectedEntry.id]: suggestions }));
    } catch (cause) {
      setGenreAnalysisError(cause instanceof Error ? cause.message : "Die Genres konnten nicht aus dem Flyer erkannt werden.");
    } finally {
      setGenreAnalysisLoading(false);
    }
  }

  async function saveDraft() {
    if (!selectedEntry || selectedEntry.type !== "event") return;
    setDraftSaving(true);
    setDraftError("");
    try {
      const saved = persistedDraft
        ? await updatePersistedDraft({
            ...draft,
            id: persistedDraft.id,
            calendarEntryId: selectedEntry.id,
            mentionActorIds: draft.mentionIds,
          })
        : await createDraft(selectedEntry.id, {
            caption: draft.caption,
            hashtags: draft.hashtags,
            mentionActorIds: draft.mentionIds,
            genres: draft.genres,
            status: draft.status,
          });
      setLocalDrafts((current) => ({
        ...current,
        [selectedEntry.id]: {
          caption: saved.caption,
          hashtags: saved.hashtags,
          mentionIds: saved.mentionActorIds,
          genres: saved.genres,
          status: saved.status,
        },
      }));
    } catch (cause) {
      setDraftError(cause instanceof Error ? cause.message : "Der Redaktionsentwurf konnte nicht gespeichert werden.");
    } finally {
      setDraftSaving(false);
    }
  }

  function toggleMention(id: string) {
    const mentionIds = draft.mentionIds.includes(id)
      ? draft.mentionIds.filter((item) => item !== id)
      : [...draft.mentionIds, id];
    updateDraft({
      mentionIds,
      caption: selectedEntry && selectedVenue
        ? buildCaption(selectedEntry, selectedVenue, actors, captionPreText, draft.genres, mentionIds)
        : draft.caption,
    });
  }

  function toggleGate(gate: keyof GateState) {
    if (!activeId) return;
    setGateStates((current) => ({
      ...current,
      [activeId]: { ...gates, [gate]: !gates[gate] },
    }));
  }

  async function handleEventFlyer(file: File | null) {
    if (!file || !selectedEntry || selectedEntry.type !== "event") return;
    const filename = file.name.toLowerCase();
    const isJpeg = file.type === "image/jpeg" || filename.endsWith(".jpg") || filename.endsWith(".jpeg");
    const isPng = file.type === "image/png" || filename.endsWith(".png");
    if (!isJpeg && !isPng) {
      setEventFlyerError("Bitte einen JPEG- oder PNG-Flyer auswählen.");
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setEventFlyerError("Der Flyer darf höchstens 10 MB groß sein.");
      return;
    }
    setEventFlyerSaving(true);
    setEventFlyerError("");
    try {
      await uploadEventFlyer(selectedEntry.id, file);
    } catch (cause) {
      setEventFlyerError(cause instanceof Error ? cause.message : "Der Event-Flyer konnte nicht gespeichert werden.");
    } finally {
      setEventFlyerSaving(false);
    }
  }

  async function removeEventFlyer() {
    if (!selectedEntry || selectedEntry.type !== "event" || !eventFlyer) return;
    setEventFlyerSaving(true);
    setEventFlyerError("");
    try {
      await deleteEventFlyer(selectedEntry.id);
    } catch (cause) {
      setEventFlyerError(cause instanceof Error ? cause.message : "Der Event-Flyer konnte nicht entfernt werden.");
    } finally {
      setEventFlyerSaving(false);
    }
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

  function moveEventToIndex(entryId: string, targetIndex: number) {
    const currentOrder = orderedEventEntries.map((entry) => entry.id);
    const currentIndex = currentOrder.indexOf(entryId);
    if (currentIndex < 0 || targetIndex < 0 || targetIndex >= currentOrder.length || currentIndex === targetIndex) return;
    const [movedId] = currentOrder.splice(currentIndex, 1);
    currentOrder.splice(targetIndex, 0, movedId);
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

  function moveEventWithKeyboard(entryId: string, direction: -1 | 1) {
    moveEvent(entryId, direction);
    setSelectedId(entryId);
  }

  function startPointerDrag(event: PointerEvent<HTMLButtonElement>, entryId: string) {
    if (event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    dragStartRef.current = { id: entryId, x: event.clientX, y: event.clientY, pointerId: event.pointerId };
    dragMovedRef.current = false;
    setDraggedId(entryId);
    setDropTargetId(entryId);
  }

  function updatePointerDrag(event: PointerEvent<HTMLButtonElement>) {
    const start = dragStartRef.current;
    if (!start || start.pointerId !== event.pointerId) return;
    const distance = Math.hypot(event.clientX - start.x, event.clientY - start.y);
    if (distance < 6) return;
    dragMovedRef.current = true;
    setIsDragging(true);
    const target = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>("[data-feed-tile-id]");
    const targetId = target?.dataset.feedTileId;
    if (!targetId || targetId === start.id) return;
    setDropTargetId(targetId);
    const targetIndex = orderedEventEntries.findIndex((entry) => entry.id === targetId);
    if (targetIndex >= 0) moveEventToIndex(start.id, targetIndex);
  }

  function finishPointerDrag(event: PointerEvent<HTMLButtonElement>) {
    const start = dragStartRef.current;
    if (start?.pointerId === event.pointerId && event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    dragStartRef.current = null;
    setDraggedId(null);
    setDropTargetId(null);
    setIsDragging(false);
  }

  function cancelPointerDrag() {
    dragStartRef.current = null;
    dragMovedRef.current = false;
    setDraggedId(null);
    setDropTargetId(null);
    setIsDragging(false);
  }

  return (
    <main className="app-page min-h-dvh bg-black px-4 py-6 text-white sm:py-8 md:px-10">
      <div className="mx-auto max-w-7xl">
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
        {draftsLoading && (
          <p className="mt-3 text-sm text-neutral-500" role="status">
            Gespeicherte Redaktionsentwürfe werden geladen ...
          </p>
        )}
        {draftsError && (
          <div className="mt-3 rounded-lg border border-red-500/30 bg-red-950/20 p-3" role="alert">
            <p className="text-sm text-red-300">{draftsError}</p>
            <button
              type="button"
              onClick={() => void reloadDrafts()}
              className="mt-3 min-h-10 rounded-lg border border-white/20 px-3 py-2 text-sm hover:bg-white/10"
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
          <div className="mt-8 grid gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(300px,420px)]">
            <section className="overflow-hidden rounded-xl border border-white/10 bg-black xl:col-span-2">
              <div className="flex flex-wrap items-start justify-between gap-3 p-4 sm:p-6">
                <div>
                  <h2 className="text-lg font-semibold">Feed-Anordnung</h2>
                  <p className="mt-2 max-w-2xl text-sm leading-6 text-neutral-400">
                    Ziehe die Kacheln in die gewünschte Instagram-Reihenfolge. Die Anordnung bleibt vorerst nur in dieser Ansicht und verändert keine Kalenderdaten.
                  </p>
                </div>
                <span className="rounded-full border border-blue-400/30 px-3 py-1 text-xs text-blue-300">nur lokal</span>
              </div>
              <p className="sr-only" id="feed-order-help">
                Tastatur: Pfeiltasten verschieben die ausgewählte Kachel, Home und Ende setzen sie an den Anfang oder das Ende. Enter öffnet den zugehörigen Entwurf.
              </p>
              <div
                className={`grid grid-cols-3 gap-px bg-neutral-800 ${isDragging ? "select-none" : ""}`}
                aria-describedby="feed-order-help"
              >
                {orderedEventEntries.map((entry, index) => {
                  const party = parties.find((item) => item.id === entry.partyId);
                  const entryFlyerUrl = entry.eventFlyer?.url ?? party?.defaultFlyer?.url ?? null;
                  const isActive = entry.id === activeId;
                  const isDragged = entry.id === draggedId;
                  const isDropTarget = entry.id === dropTargetId && isDragging && !isDragged;
                  return (
                    <button
                      key={entry.id}
                      type="button"
                      data-feed-tile-id={entry.id}
                      title={`${index + 1}. ${party?.name ?? "Unbekannte Partyreihe"} · ${entry.date}`}
                      className={`group relative aspect-square min-h-0 overflow-hidden rounded-none border-0 bg-neutral-900 text-left outline-none transition duration-150 touch-none focus-visible:z-20 focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-white ${
                        isActive ? "ring-2 ring-inset ring-white" : ""
                      } ${isDragged ? "z-10 scale-[1.03] opacity-60 shadow-2xl" : "hover:brightness-110"} ${
                        isDropTarget ? "scale-[0.96] ring-2 ring-inset ring-blue-400" : ""
                      }`}
                      onPointerDown={(event) => startPointerDrag(event, entry.id)}
                      onPointerMove={updatePointerDrag}
                      onPointerUp={finishPointerDrag}
                      onPointerCancel={cancelPointerDrag}
                      onClick={(event) => {
                        if (dragMovedRef.current) {
                          event.preventDefault();
                          event.stopPropagation();
                          dragMovedRef.current = false;
                          return;
                        }
                        setSelectedId(entry.id);
                      }}
                      onKeyDown={(event) => {
                        if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
                          event.preventDefault();
                          moveEventWithKeyboard(entry.id, -1);
                        } else if (event.key === "ArrowRight" || event.key === "ArrowDown") {
                          event.preventDefault();
                          moveEventWithKeyboard(entry.id, 1);
                        } else if (event.key === "Home") {
                          event.preventDefault();
                          moveEventToIndex(entry.id, 0);
                          setSelectedId(entry.id);
                        } else if (event.key === "End") {
                          event.preventDefault();
                          moveEventToIndex(entry.id, orderedEventEntries.length - 1);
                          setSelectedId(entry.id);
                        }
                      }}
                      aria-label={`${index + 1}. ${party?.name ?? "Unbekannte Partyreihe"}, ${entry.date}. Zum Bearbeiten auswählen.`}
                      aria-pressed={isActive}
                    >
                      <span className="absolute left-2 top-2 z-10 flex size-7 items-center justify-center rounded-full bg-blue-600 text-xs font-bold text-white shadow-lg sm:left-3 sm:top-3 sm:size-8">
                        {index + 1}
                      </span>
                      {entryFlyerUrl ? (
                        <Image
                          src={entryFlyerUrl}
                          alt={`${entry.eventFlyer ? "Event-Flyer" : "Dauerflyer"} ${party?.name ?? "Partyreihe"}`}
                          fill
                          sizes="(max-width: 640px) 33vw, (max-width: 1280px) 25vw, 20vw"
                          unoptimized
                          className="object-cover"
                        />
                      ) : (
                        <span className="flex size-full flex-col items-center justify-center bg-gradient-to-br from-neutral-800 via-neutral-950 to-blue-950/50 p-3 text-center">
                          <span className="text-[10px] font-semibold uppercase tracking-[0.2em] text-blue-300">NoizyCologne</span>
                          <span className="mt-2 line-clamp-3 text-xs text-neutral-300">{party?.name ?? "Kein Flyer"}</span>
                          <span className="mt-1 text-[10px] text-neutral-500">Flyer fehlt</span>
                        </span>
                      )}
                      <span className="sr-only">{party?.name ?? "Unbekannte Partyreihe"}, {entry.date}</span>
                    </button>
                  );
                })}
              </div>
              <p className="p-4 text-xs text-neutral-500 sm:px-6 sm:pb-6">
                Kachel anklicken = Entwurf auswählen · Ziehen = umsortieren · Pfeiltasten/Home/Ende = Tastaturalternative
              </p>

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

              <article className="mx-auto max-w-[470px] overflow-hidden rounded-xl border border-white/10 bg-black">
                <div className="flex items-center gap-3 border-b border-white/10 p-3">
                  <div className="flex size-9 shrink-0 items-center justify-center overflow-hidden rounded-full bg-white p-1">
                    <Image
                      src="/noizy-logo-black.png"
                      alt=""
                      width={36}
                      height={36}
                      className="size-full object-contain"
                    />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold">noizycologne</p>
                    <p className="text-xs text-neutral-500">Team-Entwurf</p>
                  </div>
                  <span className="text-lg leading-none text-neutral-300" aria-hidden="true">•••</span>
                </div>
                {flyerUrl ? (
                  <div className="relative aspect-[4/5] w-full bg-neutral-950">
                    <Image
                      src={flyerUrl}
                      alt={`${eventFlyer ? "Event-Flyer" : "Dauerflyer"} ${selectedParty?.name ?? "Partyreihe"}`}
                      fill
                      sizes="(max-width: 640px) 100vw, 470px"
                      loading="eager"
                      unoptimized
                      className="object-contain"
                    />
                  </div>
                ) : (
                  <div className="flex aspect-[4/5] items-center justify-center bg-neutral-900 p-8 text-center text-sm text-neutral-500">
                    Für diesen Entwurf ist noch kein Bild hinterlegt. Lade einen Event-Flyer hoch oder hinterlege einen Dauerflyer.
                  </div>
                )}
                <div className="p-3">
                  <div className="mb-3 flex items-center justify-between text-xl leading-none text-neutral-200" aria-hidden="true">
                    <div className="flex items-center gap-4">
                      <span>♡</span>
                      <span className="text-lg">▢</span>
                      <span>⌁</span>
                    </div>
                    <span>⌑</span>
                  </div>
                  <div className="space-y-2 text-sm leading-5">
                    <p className="font-semibold">noizycologne</p>
                    <p className="whitespace-pre-wrap text-neutral-300">{draft.caption || "Caption hier vorbereiten ..."}</p>
                    {draft.hashtags.trim() && <p className="whitespace-pre-wrap text-blue-300">{draft.hashtags}</p>}
                    {draft.mentionIds.length > 0 && (
                      <p className="text-blue-300">
                        {draft.mentionIds
                          .map((id) => actors.find((actor) => actor.id === id))
                          .filter((actor): actor is Actor => Boolean(actor?.instagram))
                          .map((actor) => displayHandle(actor))
                          .join(" ")}
                      </p>
                    )}
                  </div>
                </div>
              </article>
            </section>

            <section className="rounded-xl border border-white/10 bg-neutral-950 p-4 sm:p-6">
              <h2 className="text-lg font-semibold">Entwurf bearbeiten</h2>
              <p className="mt-2 text-sm leading-6 text-neutral-400">
                Änderungen werden erst mit „Entwurf speichern“ dauerhaft in PostgreSQL gespeichert.
              </p>
              <label className="mt-5 block">
                <span className="mb-2 block text-sm text-neutral-300">Redaktionsstatus</span>
                <select
                  className="min-h-12 w-full rounded-lg border border-white/15 bg-neutral-900 px-3 py-2.5 text-sm text-white outline-none focus:border-white/60"
                  value={draft.status}
                  onChange={(event) => updateDraft({ status: event.target.value as EditorialDraftStatus })}
                  disabled={draftSaving}
                >
                  <option value="DRAFT">Entwurf</option>
                  <option value="READY_FOR_REVIEW">Zur Prüfung bereit</option>
                  <option value="APPROVED">Freigegeben (nicht veröffentlicht)</option>
                </select>
              </label>
              <section className="mt-5 rounded-lg border border-white/10 p-4">
                <h3 className="text-sm font-semibold">Event-Flyer</h3>
                <p className="mt-1 text-xs leading-5 text-neutral-500">
                  Ein Event-Flyer überschreibt den Dauerflyer nur für diesen Entwurf. Es wird nichts veröffentlicht.
                </p>
                <label className="mt-3 block">
                  <span className="sr-only">Event-Flyer auswählen</span>
                  <input
                    type="file"
                    accept=".jpg,.jpeg,.png,image/jpeg,image/png"
                    disabled={eventFlyerSaving}
                    onChange={(event) => {
                      void handleEventFlyer(event.target.files?.[0] ?? null);
                      event.target.value = "";
                    }}
                    className="block w-full text-sm text-neutral-300 file:mr-4 file:rounded-lg file:border-0 file:bg-white file:px-4 file:py-2 file:font-semibold file:text-black disabled:opacity-50"
                  />
                </label>
                {eventFlyer && (
                  <button
                    type="button"
                    onClick={() => void removeEventFlyer()}
                    disabled={eventFlyerSaving}
                    className="mt-3 min-h-11 rounded-lg border border-red-400/40 px-3 py-2 text-sm text-red-300 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    Event-Flyer entfernen
                  </button>
                )}
                {eventFlyerSaving && <p className="mt-2 text-xs text-neutral-400" role="status">Event-Flyer wird gespeichert ...</p>}
                {eventFlyerError && <p className="mt-2 text-xs text-red-300" role="alert">{eventFlyerError}</p>}
                {eventFlyer && (
                  <div className="mt-4 rounded-lg border border-white/10 bg-neutral-900/60 p-3">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div>
                        <h4 className="text-sm font-semibold">Genres aus Flyer erkennen</h4>
                        <p className="mt-1 text-xs leading-5 text-neutral-500">
                          KI-Vorschläge werden nur nach deiner Prüfung übernommen.
                        </p>
                      </div>
                      <button
                        type="button"
                        onClick={() => void analyzeGenres()}
                        disabled={genreAnalysisLoading || eventFlyerSaving}
                        className="min-h-10 rounded-lg border border-white/20 px-3 py-2 text-sm hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        {genreAnalysisLoading ? "Flyer wird analysiert ..." : "Genres erkennen"}
                      </button>
                    </div>
                    {genreAnalysisError && <p className="mt-3 text-sm text-red-300" role="alert">{genreAnalysisError}</p>}
                    {currentGenreSuggestions.length > 0 && (
                      <div className="mt-3 border-t border-white/10 pt-3">
                        <p className="text-xs text-neutral-400">
                          Vorschläge: {currentGenreSuggestions.map((suggestion) => `${suggestion.genre} (${suggestion.uncertain ? "unsicher" : "OCR"}, ${Math.round(suggestion.confidence * 100)} % OCR-Konfidenz)`).join(", ")}
                        </p>
                        <button
                          type="button"
                          onClick={applyGenreSuggestions}
                          disabled={draftSaving}
                          className="mt-3 min-h-10 rounded-lg bg-white px-3 py-2 text-sm font-semibold text-black disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          Vorschläge übernehmen
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </section>
              <label className="mt-5 block">
                <span className="mb-2 block text-sm text-neutral-300">Caption</span>
                <textarea
                  className="min-h-44 w-full rounded-lg border border-white/15 bg-neutral-900 px-3 py-2.5 text-sm text-white outline-none focus:border-white/60"
                  value={draft.caption}
                  onChange={(event) => updateDraft({ caption: event.target.value })}
                />
              </label>
              <fieldset className="mt-5">
                <legend className="text-sm text-neutral-300">Genres</legend>
                <p className="mt-1 text-xs leading-5 text-neutral-500">
                  Bitte die Genres vom Flyer redaktionell auswählen. Die Auswahl wird gespeichert und in die Caption übernommen.
                </p>
                <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3">
                  {genresLoading && <p className="col-span-full text-sm text-neutral-500" role="status">Genres werden aus der Datenbank geladen ...</p>}
                  {genresError && <p className="col-span-full text-sm text-red-300" role="alert">{genresError}</p>}
                  {!genresLoading && !genresError && availableGenres.length === 0 && <p className="col-span-full text-sm text-neutral-500">Keine aktiven Genres in den Einstellungen vorhanden.</p>}
                  {availableGenres.map((genre) => (
                    <label key={genre.id} className="flex min-h-11 items-center gap-2 rounded-lg bg-neutral-900 px-3 py-2 text-sm">
                      <input
                        type="checkbox"
                        className="size-4 accent-white"
                        checked={draft.genres.includes(genre.displayName)}
                        onChange={() => toggleGenre(genre.displayName)}
                        disabled={draftSaving}
                      />
                      <span>{genre.displayName}</span>
                    </label>
                  ))}
                </div>
              </fieldset>
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
              <button
                type="button"
                onClick={() => void saveDraft()}
                disabled={draftSaving || draftsLoading || Boolean(draftsError)}
                className="mt-5 min-h-12 w-full rounded-lg bg-white px-4 py-2 text-sm font-semibold text-black disabled:cursor-not-allowed disabled:opacity-50"
              >
                {draftSaving ? "Entwurf wird gespeichert ..." : persistedDraft ? "Entwurf speichern" : "Entwurf anlegen"}
              </button>
              {draftError && <p className="mt-2 text-sm text-red-300" role="alert">{draftError}</p>}
            </section>
          </div>
        )}
      </div>
    </main>
  );
}
