
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

export function CatalogProvider({
  children,
}: {
  children: ReactNode;
}) {
  const [parties, setParties] = useState<PartySeries[]>(
    () =>
      partySeries.map((party) => ({
        ...party,
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
