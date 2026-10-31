
"use client";

import {
  createContext,
  useContext,
  useState,
  type Dispatch,
  type ReactNode,
  type SetStateAction,
} from "react";

import { locations, partySeries } from "./kalender/catalog";

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

export type PartySeries = CatalogItem & {
    color: string;
  residentDjIds: string[];
  otherDjIds: string[];
  organizerIds: string[];
  djMentionDefault: DjMentionDefault;
};

type CatalogContextValue = {
  parties: PartySeries[];
  setParties: Dispatch<SetStateAction<PartySeries[]>>;

  venues: CatalogItem[];
  setVenues: Dispatch<SetStateAction<CatalogItem[]>>;

  actors: Actor[];
  setActors: Dispatch<SetStateAction<Actor[]>>;
};

const CatalogContext =
  createContext<CatalogContextValue | null>(null);


export const PARTY_COLORS = [
  "#E11D48", // Pink
  "#0891B2", // Türkis
  "#8B5CF6", // Violett
  "#EA580C", // Orange
  "#2563EB", // Blau
  "#16A34A", // Grün
  "#DB2777", // Magenta
  "#CA8A04", // Gold
  "#4F46E5", // Indigo
  "#0D9488", // Teal
  "#DC2626", // Rot
  "#9333EA", // Lila
];


export function CatalogProvider({
  children,
}: {
  children: ReactNode;
}) {
  const [parties, setParties] = useState<PartySeries[]>(
    () =>
      partySeries.map((party, index) => ({
        ...party,
        color: PARTY_COLORS[index % PARTY_COLORS.length],
        instagram: "",
        residentDjIds: [],
        otherDjIds: [],
        organizerIds: [],
        djMentionDefault: "manual" as const,
      }))
  );

  const [venues, setVenues] = useState<CatalogItem[]>(
    () =>
      locations.map((location) => ({
        ...location,
        instagram: location.instagram ?? "",
      }))
  );

  const [actors, setActors] = useState<Actor[]>([]);

  return (
    <CatalogContext.Provider
      value={{
        parties,
        setParties,
        venues,
        setVenues,
        actors,
        setActors,
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
