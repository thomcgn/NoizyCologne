"use client";

import {
  createContext,
  useContext,
  useEffect,
  useCallback,
  useState,
  type ReactNode,
} from "react";

export type CatalogItem = {
  websiteUrl?: string;
  eventsUrl?: string;
  discoveryEnabled?: boolean;
  lastDiscoveryAt?: string | null;
  discoveryStatus?: string;
  id: string;
  name: string;
  instagram: string;
};

export type ActorKind = "person" | "collective";
export type ActorRole = "dj" | "organizer";

export type Actor = {
  id: string;
  name: string;
  kind: ActorKind;
  roles: ActorRole[];
  instagram: string;
  collectiveIds: string[];
};

export type DjMentionDefault = "residents" | "manual";

export type RecurrenceRule =
  | { kind: "weekly"; weekday: number }
  | { kind: "monthly_weekday"; weekday: number; occurrence: number }
  | { kind: "other"; description: string };

export type PartyFlyer = {
  id: string;
  originalFilename: string;
  mediaType: "image/jpeg" | "image/png";
  fileSize: number;
  url: string;
};

export type GenreSuggestion = {
  genre: string;
  confidence: number;
  uncertain: boolean;
};

function fallbackApiError(status: number, resource: string): string {
  if (status === 401) return "Die Anmeldung ist abgelaufen. Bitte erneut anmelden.";
  if (status === 403) return "Diese Aktion ist für dein Team-Konto nicht freigegeben.";
  if (status === 404) return `${resource} wurde nicht gefunden. Bitte aktualisieren und erneut versuchen.`;
  if (status === 409) return `${resource} konnte nicht gespeichert werden, weil sich die Daten inzwischen geändert haben.`;
  if (status === 413) return "Die Datei ist zu groß. Bitte wähle ein Bild bis höchstens 8 MB.";
  if (status === 422) return `Die Daten für ${resource.toLowerCase()} konnten nicht verarbeitet werden. Bitte prüfe die Eingaben.`;
  if (status === 502 || status === 503) return `${resource} ist momentan nicht erreichbar. Bitte prüfe, ob das Backend läuft, und versuche es erneut.`;
  if (status === 504) return `${resource} hat zu lange gebraucht. Bitte versuche es erneut.`;
  if (status >= 500) return `Im Backend ist ein technischer Fehler bei ${resource.toLowerCase()} aufgetreten. Bitte versuche es erneut.`;
  return `${resource} antwortete mit HTTP ${status}.`;
}

export const PARTY_COLORS = [
  "#E11D48",
  "#0891B2",
  "#8B5CF6",
  "#EA580C",
  "#2563EB",
  "#16A34A",
  "#DB2777",
  "#CA8A04",
  "#4F46E5",
  "#0D9488",
  "#DC2626",
  "#9333EA",
];

export type PartySeries = CatalogItem & {
  color: string;
  residentDjIds: string[];
  otherDjIds: string[];
  organizerIds: string[];
  standardGenres: string[];
  djMentionDefault: DjMentionDefault;
  recurrence: RecurrenceRule | null;
  defaultFlyer: PartyFlyer | null;
};

export type EditorialDraftStatus = "DRAFT" | "READY_FOR_REVIEW" | "APPROVED";

export type EditorialPostDraft = {
  approvalNeedsReview?: boolean;
  publicationBlockers?: string[];
  id: string;
  calendarEntryId: string;
  caption: string;
  hashtags: string;
  mentionActorIds: string[];
  genres: string[];
  status: EditorialDraftStatus;
  musicTrackId: string | null;
};

export type MusicTrack = {
  id: string;
  artist: string;
  title: string;
  genres: string[];
  partySeriesIds: string[];
  youtubeVideoId: string;
  spotifyTrackUrl: string;
  previewStartSeconds: number;
  preferredPositionSeconds: number;
  lastUsedAt: string | null;
  score: number;
  matchReasons: string[];
};

export type MusicTrackInput = Omit<MusicTrack, "id" | "lastUsedAt" | "score" | "matchReasons">;

type EntryBase = {
  eventStatus?: "ACTIVE" | "CANCELLED";
  id: string;
  date: string; // Lokales Kalenderdatum: YYYY-MM-DD (ohne Zeitzonen-Verschiebung)
  locationId: string;
  notes: string;
  entryPrice: string;
};

export type CalendarEntry = EntryBase &
  (
    | {
        type: "event";
        partyId: string;
        startTime: string;
        mentionDjIds: string[];
        eventFlyer: PartyFlyer | null;
      }
    | {
        type: "closure";
        reason: string;
        eventFlyer: null;
      }
  );

export type CalendarEntryPayload = Omit<EntryBase, "id"> &
  (
    | {
        type: "event";
        partyId: string;
        startTime: string;
        mentionDjIds: string[];
      }
    | {
        type: "closure";
        reason: string;
      }
  );

export function toDateKey(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

type CatalogContextValue = {
  parties: PartySeries[];
  partiesLoading: boolean;
  partiesError: string | null;
  reloadParties: () => Promise<void>;
  createParty: (party: Omit<PartySeries, "id">) => Promise<PartySeries>;
  updateParty: (party: PartySeries) => Promise<PartySeries>;
  deleteParty: (id: string) => Promise<void>;
  uploadPartyFlyer: (partyId: string, file: File) => Promise<PartyFlyer>;
  deletePartyFlyer: (partyId: string) => Promise<void>;
  uploadEventFlyer: (entryId: string, file: File) => Promise<PartyFlyer>;
  analyzeEventFlyerGenres: (entryId: string) => Promise<GenreSuggestion[]>;
  deleteEventFlyer: (entryId: string) => Promise<void>;
  drafts: EditorialPostDraft[];
  draftsLoading: boolean;
  draftsError: string | null;
  reloadDrafts: () => Promise<void>;
  createDraft: (calendarEntryId: string, draft: Omit<EditorialPostDraft, "id" | "calendarEntryId">) => Promise<EditorialPostDraft>;
  updateDraft: (draft: EditorialPostDraft) => Promise<EditorialPostDraft>;
  musicTracks: MusicTrack[];
  musicTracksLoading: boolean;
  musicTracksError: string | null;
  reloadMusicTracks: (calendarEntryId?: string) => Promise<void>;
  createMusicTrack: (track: MusicTrackInput) => Promise<MusicTrack>;
  updateMusicTrack: (track: MusicTrack) => Promise<MusicTrack>;
  venues: CatalogItem[];
  venuesLoading: boolean;
  venuesError: string | null;
  reloadVenues: () => Promise<void>;
  createVenue: (venue: Omit<CatalogItem, "id">) => Promise<CatalogItem>;
  updateVenue: (venue: CatalogItem) => Promise<CatalogItem>;
  deleteVenue: (id: string) => Promise<void>;
  actors: Actor[];
  actorsLoading: boolean;
  actorsError: string | null;
  reloadActors: () => Promise<void>;
  createActor: (actor: Omit<Actor, "id">) => Promise<Actor>;
  updateActor: (actor: Actor) => Promise<Actor>;
  deleteActor: (id: string) => Promise<void>;
  entries: CalendarEntry[];
  entriesLoading: boolean;
  entriesError: string | null;
  reloadEntries: () => Promise<void>;
  createEntry: (entry: CalendarEntryPayload) => Promise<CalendarEntry>;
  updateEntry: (entry: CalendarEntryPayload & { id: string }) => Promise<CalendarEntry>;
  deleteEntry: (id: string) => Promise<void>;
};

const CatalogContext = createContext<CatalogContextValue | null>(null);

export function CatalogProvider({ children }: { children: ReactNode }) {
  const [parties, setParties] = useState<PartySeries[]>([]);
  const [partiesLoading, setPartiesLoading] = useState(true);
  const [partiesError, setPartiesError] = useState<string | null>(null);

  async function readPartyError(response: Response): Promise<string> {
    try {
      const body: unknown = await response.json();
      if (typeof body === "object" && body !== null) {
        const errorBody = body as Record<string, unknown>;
        const message = [errorBody.message, errorBody.detail, errorBody.title]
          .find((value): value is string =>
            typeof value === "string" && value.trim().length > 0
          );
        if (message) return message;
      }
    } catch {
      // Die Statusmeldung darunter bleibt verständlich, auch wenn kein JSON vorliegt.
    }

    return fallbackApiError(response.status, "Die Partyreihenverwaltung");
  }

  const parsePartyFlyer = useCallback((body: unknown): PartyFlyer => {
    if (typeof body !== "object" || body === null) {
      throw new Error("Die Flyer-API lieferte ungültige Metadaten.");
    }
    const flyer = body as Record<string, unknown>;
    if (
      typeof flyer.id !== "string" ||
      typeof flyer.originalFilename !== "string" ||
      (flyer.mediaType !== "image/jpeg" && flyer.mediaType !== "image/png") ||
      typeof flyer.fileSize !== "number" ||
      !Number.isSafeInteger(flyer.fileSize) ||
      typeof flyer.url !== "string"
    ) {
      throw new Error("Die Flyer-API lieferte ungültige Metadaten.");
    }
    return {
      id: flyer.id,
      originalFilename: flyer.originalFilename,
      mediaType: flyer.mediaType,
      fileSize: flyer.fileSize,
      url: flyer.url,
    };
  }, []);

  const parsePartyResponse = useCallback((body: unknown): PartySeries => {
    if (typeof body !== "object" || body === null) {
      throw new Error("Die Partyreihen-API lieferte ein unerwartetes Antwortformat.");
    }

    const party = body as Record<string, unknown>;
    const validIds = (value: unknown): value is string[] =>
      Array.isArray(value) && value.every((id) => typeof id === "string");

    let defaultFlyer: PartyFlyer | null = null;
    if (party.defaultFlyer !== null && party.defaultFlyer !== undefined) {
      defaultFlyer = parsePartyFlyer(party.defaultFlyer);
    }

    if (
      typeof party.id !== "string" ||
      typeof party.name !== "string" ||
      typeof party.instagram !== "string" ||
      typeof party.color !== "string" ||
      !validIds(party.residentDjIds) ||
      !validIds(party.otherDjIds) ||
      !validIds(party.organizerIds) ||
      !validIds(party.standardGenres) ||
      (party.djMentionDefault !== "residents" && party.djMentionDefault !== "manual")
    ) {
      throw new Error("Die Partyreihen-API lieferte ein unerwartetes Antwortformat.");
    }

    let recurrence: RecurrenceRule | null = null;
    if (party.recurrence !== null) {
      if (typeof party.recurrence !== "object" || party.recurrence === null) {
        throw new Error("Die Partyreihen-API lieferte einen ungültigen Turnus.");
      }
      const rule = party.recurrence as Record<string, unknown>;
      if (
        (rule.kind === "weekly" || rule.kind === "monthly_weekday") &&
        typeof rule.weekday === "number" &&
        Number.isInteger(rule.weekday) &&
        rule.weekday >= 0 &&
        rule.weekday <= 6
      ) {
        if (rule.kind === "weekly") {
          recurrence = { kind: "weekly", weekday: rule.weekday };
        } else if (
          typeof rule.occurrence === "number" &&
          Number.isInteger(rule.occurrence) &&
          (rule.occurrence === -1 || (rule.occurrence >= 1 && rule.occurrence <= 4))
        ) {
          recurrence = {
            kind: "monthly_weekday",
            weekday: rule.weekday,
            occurrence: rule.occurrence,
          };
        }
      } else if (rule.kind === "other" && typeof rule.description === "string" && rule.description.trim()) {
        recurrence = { kind: "other", description: rule.description };
      }

      if (!recurrence) {
        throw new Error("Die Partyreihen-API lieferte einen ungültigen Turnus.");
      }
    }

    return {
      id: party.id,
      name: party.name,
      instagram: party.instagram,
      color: party.color,
      residentDjIds: party.residentDjIds,
      otherDjIds: party.otherDjIds,
      organizerIds: party.organizerIds,
      standardGenres: party.standardGenres,
      djMentionDefault: party.djMentionDefault,
      recurrence,
      defaultFlyer,
    };
  }, [parsePartyFlyer]);

  const reloadParties = useCallback(async () => {
    setPartiesLoading(true);
    setPartiesError(null);

    try {
      const response = await fetch("/api/v1/parties", {
        cache: "no-store",
        credentials: "same-origin",
      });
      if (!response.ok) {
        throw new Error(await readPartyError(response));
      }

      const body: unknown = await response.json();
      if (!Array.isArray(body)) {
        throw new Error("Die Partyreihen-API lieferte kein gültiges Listenformat.");
      }

      setParties(body.map(parsePartyResponse));
    } catch (cause) {
      setParties([]);
      setPartiesError(
        cause instanceof Error
          ? `Partyreihen konnten nicht geladen werden: ${cause.message}`
          : "Partyreihen konnten wegen eines unbekannten Netzwerkfehlers nicht geladen werden."
      );
    } finally {
      setPartiesLoading(false);
    }
  }, [parsePartyResponse]);

  useEffect(() => {
    const loadTimer = window.setTimeout(() => {
      void reloadParties();
    }, 0);

    return () => window.clearTimeout(loadTimer);
  }, [reloadParties]);

  const [drafts, setDrafts] = useState<EditorialPostDraft[]>([]);
  const [draftsLoading, setDraftsLoading] = useState(true);
  const [draftsError, setDraftsError] = useState<string | null>(null);

  const [musicTracks, setMusicTracks] = useState<MusicTrack[]>([]);
  const [musicTracksLoading, setMusicTracksLoading] = useState(false);
  const [musicTracksError, setMusicTracksError] = useState<string | null>(null);

  const parseDraftResponse = useCallback((body: unknown): EditorialPostDraft => {
    if (typeof body !== "object" || body === null) {
      throw new Error("Die Redaktions-API lieferte ein unerwartetes Antwortformat.");
    }
    const draft = body as Record<string, unknown>;
    const validStatus = draft.status === "DRAFT" || draft.status === "READY_FOR_REVIEW" || draft.status === "APPROVED";
    const validIds = Array.isArray(draft.mentionActorIds) && draft.mentionActorIds.every((id) => typeof id === "string");
    if (
      typeof draft.id !== "string" ||
      typeof draft.calendarEntryId !== "string" ||
      typeof draft.caption !== "string" ||
      typeof draft.hashtags !== "string" ||
      !validIds ||
      !Array.isArray(draft.genres) ||
      !draft.genres.every((genre) => typeof genre === "string") ||
      !(draft.musicTrackId === null || typeof draft.musicTrackId === "string") ||
      !validStatus
    ) {
      throw new Error("Die Redaktions-API lieferte ein unerwartetes Antwortformat.");
    }
    return {
      id: draft.id,
      calendarEntryId: draft.calendarEntryId,
      caption: draft.caption,
      hashtags: draft.hashtags,
      mentionActorIds: draft.mentionActorIds as string[],
      genres: draft.genres as string[],
      status: draft.status as EditorialDraftStatus,
      musicTrackId: draft.musicTrackId as string | null,
      approvalNeedsReview: draft.approvalNeedsReview === true,
      publicationBlockers: Array.isArray(draft.publicationBlockers) ? draft.publicationBlockers.filter((item): item is string => typeof item === "string") : [],
    };
  }, []);

  const reloadDrafts = useCallback(async () => {
    setDraftsLoading(true);
    setDraftsError(null);
    try {
      const response = await fetch("/api/v1/editorial-drafts", {
        cache: "no-store",
        credentials: "same-origin",
      });
      if (!response.ok) throw new Error(await readPartyError(response));
      const body: unknown = await response.json();
      if (!Array.isArray(body)) throw new Error("Die Redaktions-API lieferte kein gültiges Listenformat.");
      setDrafts(body.map(parseDraftResponse));
    } catch (cause) {
      setDrafts([]);
      setDraftsError(cause instanceof Error ? `Redaktionsentwürfe konnten nicht geladen werden: ${cause.message}` : "Redaktionsentwürfe konnten nicht geladen werden.");
    } finally {
      setDraftsLoading(false);
    }
  }, [parseDraftResponse]);

  useEffect(() => {
    const loadTimer = window.setTimeout(() => { void reloadDrafts(); }, 0);
    return () => window.clearTimeout(loadTimer);
  }, [reloadDrafts]);

  async function createDraft(calendarEntryId: string, draft: Omit<EditorialPostDraft, "id" | "calendarEntryId">) {
    const response = await fetch(`/api/v1/calendar-entries/${encodeURIComponent(calendarEntryId)}/editorial-drafts`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify(draft),
    });
    if (!response.ok) throw new Error(await readPartyError(response));
    const created = parseDraftResponse(await response.json());
    setDrafts((current) => [...current, created]);
    return created;
  }

  async function updateDraft(draft: EditorialPostDraft) {
    const response = await fetch(`/api/v1/editorial-drafts/${encodeURIComponent(draft.id)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify({
        caption: draft.caption,
        hashtags: draft.hashtags,
        mentionActorIds: draft.mentionActorIds,
        genres: draft.genres,
        status: draft.status,
        musicTrackId: draft.musicTrackId,
      }),
    });
    if (!response.ok) throw new Error(await readPartyError(response));
    const updated = parseDraftResponse(await response.json());
    setDrafts((current) => current.map((item) => item.id === updated.id ? updated : item));
    return updated;
  }

  const parseMusicTrack = useCallback((body: unknown): MusicTrack => {
    if (typeof body !== "object" || body === null) throw new Error("Die Musik-API lieferte ein unerwartetes Antwortformat.");
    const value = body as Record<string, unknown>;
    const validStrings = (key: string) => Array.isArray(value[key]) && value[key].every((item) => typeof item === "string");
    if (typeof value.id !== "string" || typeof value.artist !== "string" || typeof value.title !== "string"
      || !validStrings("genres") || !validStrings("partySeriesIds") || typeof value.youtubeVideoId !== "string"
      || typeof value.spotifyTrackUrl !== "string" || typeof value.previewStartSeconds !== "number"
      || typeof value.preferredPositionSeconds !== "number" || !(value.lastUsedAt === null || typeof value.lastUsedAt === "string")
      || typeof value.score !== "number" || !validStrings("matchReasons")) {
      throw new Error("Die Musik-API lieferte ein unerwartetes Antwortformat.");
    }
    return {
      id: value.id,
      artist: value.artist,
      title: value.title,
      genres: value.genres as string[],
      partySeriesIds: value.partySeriesIds as string[],
      youtubeVideoId: value.youtubeVideoId,
      spotifyTrackUrl: value.spotifyTrackUrl,
      previewStartSeconds: value.previewStartSeconds,
      preferredPositionSeconds: value.preferredPositionSeconds,
      lastUsedAt: value.lastUsedAt as string | null,
      score: value.score,
      matchReasons: value.matchReasons as string[],
    };
  }, []);

  const reloadMusicTracks = useCallback(async (calendarEntryId?: string) => {
    setMusicTracksLoading(true);
    setMusicTracksError(null);
    try {
      const query = calendarEntryId ? `?calendarEntryId=${encodeURIComponent(calendarEntryId)}` : "";
      const response = await fetch(`/api/v1/music/tracks${query}`, { cache: "no-store", credentials: "same-origin" });
      if (!response.ok) throw new Error(await readPartyError(response));
      const body: unknown = await response.json();
      if (!Array.isArray(body)) throw new Error("Die Musik-API lieferte kein gültiges Listenformat.");
      setMusicTracks(body.map(parseMusicTrack));
    } catch (cause) {
      setMusicTracks([]);
      setMusicTracksError(cause instanceof Error ? cause.message : "Musikvorschläge konnten nicht geladen werden.");
    } finally {
      setMusicTracksLoading(false);
    }
  }, [parseMusicTrack]);

  async function createMusicTrack(track: MusicTrackInput) {
    const response = await fetch("/api/v1/music/tracks", {
      method: "POST", headers: { "Content-Type": "application/json" }, credentials: "same-origin",
      body: JSON.stringify(track),
    });
    if (!response.ok) throw new Error(await readPartyError(response));
    const created = parseMusicTrack(await response.json());
    setMusicTracks((current) => [created, ...current]);
    return created;
  }

  async function updateMusicTrack(track: MusicTrack) {
    const response = await fetch(`/api/v1/music/tracks/${encodeURIComponent(track.id)}`, {
      method: "PUT", headers: { "Content-Type": "application/json" }, credentials: "same-origin",
      body: JSON.stringify(track),
    });
    if (!response.ok) throw new Error(await readPartyError(response));
    const updated = parseMusicTrack(await response.json());
    setMusicTracks((current) => current.map((item) => item.id === updated.id ? updated : item));
    return updated;
  }

  async function createParty(party: Omit<PartySeries, "id">) {
    const response = await fetch("/api/v1/parties", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify(party),
    });

    if (!response.ok) {
      throw new Error(await readPartyError(response));
    }

    const created = parsePartyResponse(await response.json());
    setParties((current) => [...current, created]);
    return created;
  }

  async function updateParty(party: PartySeries) {
    const response = await fetch(
      `/api/v1/parties/${encodeURIComponent(party.id)}`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({
          name: party.name,
          instagram: party.instagram,
          color: party.color,
          residentDjIds: party.residentDjIds,
          otherDjIds: party.otherDjIds,
          organizerIds: party.organizerIds,
          standardGenres: party.standardGenres,
          djMentionDefault: party.djMentionDefault,
          recurrence: party.recurrence,
        }),
      }
    );

    if (!response.ok) {
      throw new Error(await readPartyError(response));
    }

    const updated = parsePartyResponse(await response.json());
    setParties((current) =>
      current.map((item) => (item.id === updated.id ? updated : item))
    );
    return updated;
  }

  async function deleteParty(id: string) {
    const response = await fetch(`/api/v1/parties/${encodeURIComponent(id)}`, {
      method: "DELETE",
      credentials: "same-origin",
    });
    if (!response.ok) throw new Error(await readPartyError(response));
    setParties((current) => current.filter((party) => party.id !== id));
  }

  async function uploadPartyFlyer(partyId: string, file: File) {
    const formData = new FormData();
    formData.append("file", file);
    const response = await fetch(
      `/api/v1/parties/${encodeURIComponent(partyId)}/default-flyer`,
      {
        method: "POST",
        credentials: "same-origin",
        body: formData,
      }
    );
    if (!response.ok) {
      throw new Error(await readPartyError(response));
    }
    const updated = parsePartyFlyer(await response.json());
    setParties((current) => current.map((party) =>
      party.id === partyId ? { ...party, defaultFlyer: updated } : party
    ));
    return updated;
  }

  async function deletePartyFlyer(partyId: string) {
    const response = await fetch(
      `/api/v1/parties/${encodeURIComponent(partyId)}/default-flyer`,
      { method: "DELETE", credentials: "same-origin" }
    );
    if (!response.ok) {
      throw new Error(await readPartyError(response));
    }
    setParties((current) => current.map((party) =>
      party.id === partyId ? { ...party, defaultFlyer: null } : party
    ));
  }

  const [venues, setVenues] = useState<CatalogItem[]>([]);
  const [venuesLoading, setVenuesLoading] = useState(true);
  const [venuesError, setVenuesError] = useState<string | null>(null);

  async function readVenueError(response: Response): Promise<string> {
    try {
      const body: unknown = await response.json();
      if (
        typeof body === "object" &&
        body !== null &&
        "message" in body &&
        typeof body.message === "string" &&
        body.message.trim()
      ) {
        return body.message;
      }
    } catch {
      // Die Statusmeldung darunter bleibt verständlich, auch wenn kein JSON vorliegt.
    }

    return fallbackApiError(response.status, "Die Locationverwaltung");
  }

  function parseVenueResponse(body: unknown): CatalogItem {
    if (
      typeof body !== "object" ||
      body === null
    ) {
      throw new Error("Die Location-API lieferte ein unerwartetes Antwortformat.");
    }

    const venue = body as Record<string, unknown>;
    if (
      typeof venue.id !== "string" ||
      typeof venue.name !== "string" ||
      typeof venue.instagram !== "string"
    ) {
      throw new Error("Die Location-API lieferte ein unerwartetes Antwortformat.");
    }

    return {
      websiteUrl: typeof venue.websiteUrl === "string" ? venue.websiteUrl : "",
      eventsUrl: typeof venue.eventsUrl === "string" ? venue.eventsUrl : "",
      discoveryEnabled: venue.discoveryEnabled === true,
      lastDiscoveryAt: typeof venue.lastDiscoveryAt === "string" ? venue.lastDiscoveryAt : null,
      discoveryStatus: typeof venue.discoveryStatus === "string" ? venue.discoveryStatus : "NOT_CHECKED",
      id: venue.id,
      name: venue.name,
      instagram: venue.instagram,
    };
  }

  const reloadVenues = useCallback(async () => {
    setVenuesLoading(true);
    setVenuesError(null);

    try {
      const response = await fetch("/api/v1/venues", { cache: "no-store" });
      if (!response.ok) {
        throw new Error(await readVenueError(response));
      }

      const body: unknown = await response.json();
      if (!Array.isArray(body)) {
        throw new Error("Die Location-API lieferte kein gültiges Listenformat.");
      }

      setVenues(body.map(parseVenueResponse));
    } catch (cause) {
      setVenues([]);
      setVenuesError(
        cause instanceof Error
          ? `Locations konnten nicht geladen werden: ${cause.message}`
          : "Locations konnten wegen eines unbekannten Netzwerkfehlers nicht geladen werden."
      );
    } finally {
      setVenuesLoading(false);
    }
  }, []);

  useEffect(() => {
    const loadTimer = window.setTimeout(() => {
      void reloadVenues();
    }, 0);

    return () => window.clearTimeout(loadTimer);
  }, [reloadVenues]);

  async function createVenue(venue: Omit<CatalogItem, "id">) {
    const response = await fetch("/api/v1/venues", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(venue),
    });

    if (!response.ok) {
      throw new Error(await readVenueError(response));
    }

    const created = parseVenueResponse(await response.json());
    setVenues((current) => [...current, created]);
    return created;
  }

  async function updateVenue(venue: CatalogItem) {
    const response = await fetch(
      `/api/v1/venues/${encodeURIComponent(venue.id)}`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(venue),
      }
    );

    if (!response.ok) {
      throw new Error(await readVenueError(response));
    }

    const updated = parseVenueResponse(await response.json());
    setVenues((current) =>
      current.map((item) => (item.id === updated.id ? updated : item))
    );
    return updated;
  }

  async function deleteVenue(id: string) {
    const response = await fetch(`/api/v1/venues/${encodeURIComponent(id)}`, {
      method: "DELETE",
      credentials: "same-origin",
    });
    if (!response.ok) throw new Error(await readVenueError(response));
    setVenues((current) => current.filter((venue) => venue.id !== id));
  }

  const [actors, setActors] = useState<Actor[]>([]);
  const [actorsLoading, setActorsLoading] = useState(true);
  const [actorsError, setActorsError] = useState<string | null>(null);

  async function readActorError(response: Response): Promise<string> {
    try {
      const body: unknown = await response.json();
      if (typeof body === "object" && body !== null) {
        const errorBody = body as Record<string, unknown>;
        const message = [errorBody.message, errorBody.detail, errorBody.title]
          .find((value): value is string =>
            typeof value === "string" && value.trim().length > 0
          );
        if (message) return message;
      }
    } catch {
      // Die Statusmeldung darunter bleibt verständlich, auch wenn kein JSON vorliegt.
    }

    return fallbackApiError(response.status, "Die Akteurverwaltung");
  }

  function parseActorResponse(body: unknown): Actor {
    if (typeof body !== "object" || body === null) {
      throw new Error("Die Akteur-API lieferte ein unerwartetes Antwortformat.");
    }

    const actor = body as Record<string, unknown>;
    if (
      typeof actor.id !== "string" ||
      typeof actor.name !== "string" ||
      (actor.kind !== "person" && actor.kind !== "collective") ||
      typeof actor.instagram !== "string" ||
      !Array.isArray(actor.roles) ||
      !actor.roles.every((role) => role === "dj" || role === "organizer") ||
      !Array.isArray(actor.collectiveIds) ||
      !actor.collectiveIds.every((id) => typeof id === "string")
    ) {
      throw new Error("Die Akteur-API lieferte ein unerwartetes Antwortformat.");
    }

    return {
      id: actor.id,
      name: actor.name,
      kind: actor.kind,
      roles: actor.roles,
      instagram: actor.instagram,
      collectiveIds: actor.collectiveIds,
    };
  }

  const reloadActors = useCallback(async () => {
    setActorsLoading(true);
    setActorsError(null);

    try {
      const response = await fetch("/api/v1/actors", {
        cache: "no-store",
        credentials: "same-origin",
      });
      if (!response.ok) {
        throw new Error(await readActorError(response));
      }

      const body: unknown = await response.json();
      if (!Array.isArray(body)) {
        throw new Error("Die Akteur-API lieferte kein gültiges Listenformat.");
      }

      setActors(body.map(parseActorResponse));
    } catch (cause) {
      setActors([]);
      setActorsError(
        cause instanceof Error
          ? `Akteure konnten nicht geladen werden: ${cause.message}`
          : "Akteure konnten wegen eines unbekannten Netzwerkfehlers nicht geladen werden."
      );
    } finally {
      setActorsLoading(false);
    }
  }, []);

  useEffect(() => {
    const loadTimer = window.setTimeout(() => {
      void reloadActors();
    }, 0);

    return () => window.clearTimeout(loadTimer);
  }, [reloadActors]);

  async function createActor(actor: Omit<Actor, "id">) {
    const response = await fetch("/api/v1/actors", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify(actor),
    });

    if (!response.ok) {
      throw new Error(await readActorError(response));
    }

    const created = parseActorResponse(await response.json());
    setActors((current) => [...current, created]);
    return created;
  }

  async function updateActor(actor: Actor) {
    const response = await fetch(
      `/api/v1/actors/${encodeURIComponent(actor.id)}`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({
          name: actor.name,
          kind: actor.kind,
          roles: actor.roles,
          instagram: actor.instagram,
          collectiveIds: actor.collectiveIds,
        }),
      }
    );

    if (!response.ok) {
      throw new Error(await readActorError(response));
    }

    const updated = parseActorResponse(await response.json());
    setActors((current) =>
      current.map((item) => (item.id === updated.id ? updated : item))
    );
    return updated;
  }

  async function deleteActor(id: string) {
    const response = await fetch(`/api/v1/actors/${encodeURIComponent(id)}`, {
      method: "DELETE",
      credentials: "same-origin",
    });
    if (!response.ok) throw new Error(await readActorError(response));
    setActors((current) => current.filter((actor) => actor.id !== id));
  }

  const [entries, setEntries] = useState<CalendarEntry[]>([]);
  const [entriesLoading, setEntriesLoading] = useState(true);
  const [entriesError, setEntriesError] = useState<string | null>(null);

  async function readCalendarError(response: Response): Promise<string> {
    try {
      const body: unknown = await response.json();
      if (typeof body === "object" && body !== null) {
        const errorBody = body as Record<string, unknown>;
        const message = [errorBody.message, errorBody.detail, errorBody.title]
          .find((value): value is string =>
            typeof value === "string" && value.trim().length > 0
          );
        if (message) return message;
      }
    } catch {
      // Die Statusmeldung darunter bleibt verständlich, auch wenn kein JSON vorliegt.
    }

    return fallbackApiError(response.status, "Die Kalenderverwaltung");
  }

  const parseCalendarEntry = useCallback((body: unknown): CalendarEntry => {
    if (typeof body !== "object" || body === null) {
      throw new Error("Die Kalender-API lieferte ein unerwartetes Antwortformat.");
    }

    const value = body as Record<string, unknown>;
    let eventFlyer: PartyFlyer | null = null;
    if (value.eventFlyer !== null && value.eventFlyer !== undefined) {
      eventFlyer = parsePartyFlyer(value.eventFlyer);
    }
    if (
      typeof value.id !== "string" ||
      typeof value.date !== "string" ||
      !/^\d{4}-\d{2}-\d{2}$/.test(value.date) ||
      typeof value.locationId !== "string" ||
      typeof value.type !== "string" ||
      typeof value.notes !== "string" ||
      typeof value.entryPrice !== "string" ||
      !Array.isArray(value.mentionDjIds) ||
      !value.mentionDjIds.every((id) => typeof id === "string")
    ) {
      throw new Error("Die Kalender-API lieferte ein unerwartetes Antwortformat.");
    }

    if (value.type === "event") {
      if (
        typeof value.partyId !== "string" ||
        (value.startTime !== null && typeof value.startTime !== "string")
      ) {
        throw new Error("Die Kalender-API lieferte ein ungültiges Event.");
      }
      return {
        id: value.id,
        date: value.date,
        locationId: value.locationId,
        notes: value.notes,
        entryPrice: value.entryPrice,
        eventFlyer,
        type: "event",
        eventStatus: value.eventStatus === "CANCELLED" ? "CANCELLED" : "ACTIVE",
        partyId: value.partyId,
        startTime: value.startTime ?? "",
        mentionDjIds: value.mentionDjIds,
      };
    }

    if (value.type === "closure" && typeof value.reason === "string") {
      return {
        id: value.id,
        date: value.date,
        locationId: value.locationId,
        notes: value.notes,
        entryPrice: value.entryPrice,
        eventFlyer: null,
        type: "closure",
        reason: value.reason,
      };
    }

    throw new Error("Die Kalender-API lieferte einen ungültigen Eintragstyp.");
  }, [parsePartyFlyer]);

  const reloadEntries = useCallback(async () => {
    setEntriesLoading(true);
    setEntriesError(null);

    try {
      const response = await fetch("/api/v1/calendar-entries", {
        cache: "no-store",
        credentials: "same-origin",
      });
      if (!response.ok) {
        throw new Error(await readCalendarError(response));
      }

      const body: unknown = await response.json();
      if (!Array.isArray(body)) {
        throw new Error("Die Kalender-API lieferte kein gültiges Listenformat.");
      }

      setEntries(body.map(parseCalendarEntry));
    } catch (cause) {
      setEntries([]);
      setEntriesError(
        cause instanceof Error
          ? `Kalendereinträge konnten nicht geladen werden: ${cause.message}`
          : "Kalendereinträge konnten wegen eines unbekannten Netzwerkfehlers nicht geladen werden."
      );
    } finally {
      setEntriesLoading(false);
    }
  }, [parseCalendarEntry]);

  useEffect(() => {
    const loadTimer = window.setTimeout(() => {
      void reloadEntries();
    }, 0);

    return () => window.clearTimeout(loadTimer);
  }, [reloadEntries]);

  async function createEntry(entry: CalendarEntryPayload) {
    const response = await fetch("/api/v1/calendar-entries", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify(entry),
    });

    if (!response.ok) {
      throw new Error(await readCalendarError(response));
    }

    const created = parseCalendarEntry(await response.json());
    setEntries((current) => [...current, created]);
    return created;
  }

  async function updateEntry(entry: CalendarEntryPayload & { id: string }) {
    const { id, ...payload } = entry;
    const response = await fetch(
      `/api/v1/calendar-entries/${encodeURIComponent(id)}`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify(payload),
      }
    );

    if (!response.ok) {
      throw new Error(await readCalendarError(response));
    }

    const updated = parseCalendarEntry(await response.json());
    setEntries((current) =>
      current.map((item) => (item.id === updated.id ? updated : item))
    );
    return updated;
  }

  async function deleteEntry(id: string) {
    const response = await fetch(
      `/api/v1/calendar-entries/${encodeURIComponent(id)}`,
      {
        method: "DELETE",
        credentials: "same-origin",
      }
    );

    if (!response.ok) {
      throw new Error(await readCalendarError(response));
    }

    setEntries((current) => current.filter((item) => item.id !== id));
  }

  async function uploadEventFlyer(entryId: string, file: File) {
    const formData = new FormData();
    formData.append("file", file);
    const response = await fetch(
      `/api/v1/calendar-entries/${encodeURIComponent(entryId)}/flyer`,
      { method: "POST", body: formData, credentials: "same-origin" }
    );
    if (!response.ok) throw new Error(await readCalendarError(response));
    const flyer = parsePartyFlyer(await response.json());
    setEntries((current) => current.map((entry) =>
      entry.id === entryId && entry.type === "event" ? { ...entry, eventFlyer: flyer } : entry
    ));
    return flyer;
  }

  async function analyzeEventFlyerGenres(entryId: string) {
    const response = await fetch(
      `/api/v1/calendar-entries/${encodeURIComponent(entryId)}/flyer/analysis`,
      { method: "POST", credentials: "same-origin" }
    );
    if (!response.ok) throw new Error(await readCalendarError(response));
    const body: unknown = await response.json();
    if (typeof body !== "object" || body === null || !Array.isArray((body as Record<string, unknown>).genres)) {
      throw new Error("Die Flyer-Analyse lieferte kein gültiges Ergebnis.");
    }
    const genres = (body as Record<string, unknown>).genres as unknown[];
    if (!genres.every((item) => {
      if (typeof item !== "object" || item === null) return false;
      const suggestion = item as Record<string, unknown>;
      return typeof suggestion.genre === "string"
        && typeof suggestion.confidence === "number"
        && suggestion.confidence >= 0
        && suggestion.confidence <= 1
        && typeof suggestion.uncertain === "boolean";
    })) {
      throw new Error("Die Flyer-Analyse lieferte ungültige Genre-Vorschläge.");
    }
    return genres as GenreSuggestion[];
  }

  async function deleteEventFlyer(entryId: string) {
    const response = await fetch(
      `/api/v1/calendar-entries/${encodeURIComponent(entryId)}/flyer`,
      { method: "DELETE", credentials: "same-origin" }
    );
    if (!response.ok) throw new Error(await readCalendarError(response));
    setEntries((current) => current.map((entry) =>
      entry.id === entryId && entry.type === "event" ? { ...entry, eventFlyer: null } : entry
    ));
  }

  return (
    <CatalogContext.Provider
      value={{
        parties,
        partiesLoading,
        partiesError,
        reloadParties,
        createParty,
        updateParty,
        deleteParty,
        uploadPartyFlyer,
        deletePartyFlyer,
        uploadEventFlyer,
        analyzeEventFlyerGenres,
        deleteEventFlyer,
        drafts,
        draftsLoading,
        draftsError,
        reloadDrafts,
        createDraft,
        updateDraft,
        musicTracks,
        musicTracksLoading,
        musicTracksError,
        reloadMusicTracks,
        createMusicTrack,
        updateMusicTrack,
        venues,
        venuesLoading,
        venuesError,
        reloadVenues,
        createVenue,
        updateVenue,
        deleteVenue,
        actors,
        actorsLoading,
        actorsError,
        reloadActors,
        createActor,
        updateActor,
        deleteActor,
        entries,
        entriesLoading,
        entriesError,
        reloadEntries,
        createEntry,
        updateEntry,
        deleteEntry,
      }}
    >
      {children}
    </CatalogContext.Provider>
  );
}

export function useCatalog() {
  const context = useContext(CatalogContext);
  if (!context) {
    throw new Error(
      "useCatalog muss innerhalb des CatalogProvider verwendet werden."
    );
  }
  return context;
}
