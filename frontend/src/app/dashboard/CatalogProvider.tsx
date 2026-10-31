"use client";

import {
  createContext,
  useContext,
  useEffect,
  useCallback,
  useState,
  type Dispatch,
  type ReactNode,
  type SetStateAction,
} from "react";

export type CatalogItem = {
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
  djMentionDefault: DjMentionDefault;
  recurrence: RecurrenceRule | null;
};

type EntryBase = {
  id: string;
  date: string; // Lokales Kalenderdatum: YYYY-MM-DD (ohne Zeitzonen-Verschiebung)
  locationId: string;
  notes: string;
};

export type CalendarEntry = EntryBase &
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
  setParties: Dispatch<SetStateAction<PartySeries[]>>;
  venues: CatalogItem[];
  venuesLoading: boolean;
  venuesError: string | null;
  reloadVenues: () => Promise<void>;
  createVenue: (venue: Omit<CatalogItem, "id">) => Promise<CatalogItem>;
  updateVenue: (venue: CatalogItem) => Promise<CatalogItem>;
  actors: Actor[];
  setActors: Dispatch<SetStateAction<Actor[]>>;
  entries: CalendarEntry[];
  setEntries: Dispatch<SetStateAction<CalendarEntry[]>>;
};

const CatalogContext = createContext<CatalogContextValue | null>(null);

export function CatalogProvider({ children }: { children: ReactNode }) {
  // Partyreihen werden nicht aus einem statischen Katalog vorbefüllt.
  // Ohne persistente Partyreihen-API bleiben sie leer statt Demo-Daten anzuzeigen.
  const [parties, setParties] = useState<PartySeries[]>([]);

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

    return `Die Location-API antwortete mit HTTP ${response.status}.`;
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
        body: JSON.stringify({ name: venue.name, instagram: venue.instagram }),
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

  const [actors, setActors] = useState<Actor[]>([]);
  const [entries, setEntries] = useState<CalendarEntry[]>([]);

  return (
    <CatalogContext.Provider
      value={{
        parties,
        setParties,
        venues,
        venuesLoading,
        venuesError,
        reloadVenues,
        createVenue,
        updateVenue,
        actors,
        setActors,
        entries,
        setEntries,
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
