
"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";

import {
  useCatalog,
  type CatalogItem,
  type PartySeries,
  type DjMentionDefault,
} from "../CatalogProvider";

type Category = "parties" | "locations";
type DjAssignment = "none" | "resident" | "other";

const inputStyle =
  "w-full rounded-lg border border-white/15 bg-neutral-900 " +
  "px-3 py-2.5 text-sm text-white outline-none " +
  "focus:border-white/60";

export default function PortfolioPage() {
  const {
    parties,
    setParties,
    venues,
    setVenues,
    actors,
  } = useCatalog();

  const [category, setCategory] = useState<Category>("parties");
  const [search, setSearch] = useState("");
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  const [name, setName] = useState("");
  const [instagram, setInstagram] = useState("");
  const [error, setError] = useState("");

  // Beziehungen einer Partyreihe
  const [residentDjIds, setResidentDjIds] = useState<string[]>([]);
  const [otherDjIds, setOtherDjIds] = useState<string[]>([]);
  const [organizerIds, setOrganizerIds] = useState<string[]>([]);

  const [djMentionDefault, setDjMentionDefault] =
    useState<DjMentionDefault>("manual");

  const items: CatalogItem[] =
    category === "parties" ? parties : venues;

  const filteredItems = items.filter((item) =>
    item.name
      .toLocaleLowerCase("de-DE")
      .includes(search.toLocaleLowerCase("de-DE"))
  );

  const djActors = actors.filter(
    (actor) =>
      actor.roles.includes("dj") ||
      residentDjIds.includes(actor.id) ||
      otherDjIds.includes(actor.id)
  );

  const organizerActors = actors.filter(
    (actor) =>
      actor.roles.includes("organizer") ||
      organizerIds.includes(actor.id)
  );

  function getActorNames(ids: string[]) {
    return ids
      .map((id) => actors.find((actor) => actor.id === id)?.name)
      .filter(Boolean)
      .join(", ");
  }

  function resetForm() {
    setEditingId(null);
    setName("");
    setInstagram("");
    setResidentDjIds([]);
    setOtherDjIds([]);
    setOrganizerIds([]);
    setDjMentionDefault("manual");
    setError("");
  }

  function switchCategory(next: Category) {
    setCategory(next);
    setSearch("");
    setEditorOpen(false);
    resetForm();
  }

  function createNew() {
    resetForm();
    setEditorOpen(true);
  }

  function editItem(item: CatalogItem) {
    resetForm();

    setEditingId(item.id);
    setName(item.name);
    setInstagram(item.instagram);

    if (category === "parties") {
      const party = parties.find((p) => p.id === item.id);

      if (party) {
        setResidentDjIds([...party.residentDjIds]);
        setOtherDjIds([...party.otherDjIds]);
        setOrganizerIds([...party.organizerIds]);
        setDjMentionDefault(party.djMentionDefault);
      }
    }

    setEditorOpen(true);
  }

  function setDjAssignment(
    actorId: string,
    assignment: DjAssignment
  ) {
    setResidentDjIds((current) =>
      assignment === "resident"
        ? current.includes(actorId)
          ? current
          : [...current, actorId]
        : current.filter((id) => id !== actorId)
    );

    setOtherDjIds((current) =>
      assignment === "other"
        ? current.includes(actorId)
          ? current
          : [...current, actorId]
        : current.filter((id) => id !== actorId)
    );
  }

  function toggleOrganizer(actorId: string) {
    setOrganizerIds((current) =>
      current.includes(actorId)
        ? current.filter((id) => id !== actorId)
        : [...current, actorId]
    );
  }

  function saveItem(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const cleanName = name.trim();
    const cleanInstagram = instagram.trim().replace(/^@/, "");

    if (!cleanName) {
      setError("Bitte einen Namen eingeben.");
      return;
    }

    const duplicate = items.some(
      (item) =>
        item.id !== editingId &&
        item.name.toLocaleLowerCase("de-DE") ===
          cleanName.toLocaleLowerCase("de-DE")
    );

    if (duplicate) {
      setError("Dieser Name existiert bereits.");
      return;
    }

    if (
      cleanInstagram &&
      !/^[A-Za-z0-9._]{1,30}$/.test(cleanInstagram)
    ) {
      setError("Bitte einen gültigen Instagram-Handle eingeben.");
      return;
    }

    const id = editingId ?? crypto.randomUUID();

    if (category === "parties") {
      const updatedParty: PartySeries = {
        id,
        name: cleanName,
        instagram: cleanInstagram,
        residentDjIds,
        otherDjIds,
        organizerIds,
        djMentionDefault,
      };

      setParties((current) =>
        editingId
          ? current.map((party) =>
              party.id === editingId ? updatedParty : party
            )
          : [...current, updatedParty]
      );
    } else {
      const updatedVenue: CatalogItem = {
        id,
        name: cleanName,
        instagram: cleanInstagram,
      };

      setVenues((current) =>
        editingId
          ? current.map((venue) =>
              venue.id === editingId ? updatedVenue : venue
            )
          : [...current, updatedVenue]
      );
    }

    setEditorOpen(false);
    resetForm();
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

        <header className="mt-6">
          <p className="text-xs uppercase tracking-[0.2em] text-neutral-500">
            NoizyCologne / Stammdaten
          </p>

          <h1 className="mt-2 text-3xl font-bold">
            Party-Portfolio
          </h1>

          <p className="mt-3 text-neutral-400">
            Partyreihen, Locations und ihre Beteiligten verwalten.
          </p>
        </header>

        <Link
          href="/dashboard/portfolio/akteure"
          className="mt-5 inline-block rounded-lg border border-white/20 px-5 py-3 text-sm hover:bg-white/10"
        >
          DJs, Kollektive & Veranstalter verwalten →
        </Link>

        {/* Kategorien */}
        <div className="mt-8 flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => switchCategory("parties")}
            className={`rounded-lg px-5 py-3 text-sm ${
              category === "parties"
                ? "bg-white font-semibold text-black"
                : "border border-white/15 text-neutral-300"
            }`}
          >
            Partyreihen
          </button>

          <button
            type="button"
            onClick={() => switchCategory("locations")}
            className={`rounded-lg px-5 py-3 text-sm ${
              category === "locations"
                ? "bg-white font-semibold text-black"
                : "border border-white/15 text-neutral-300"
            }`}
          >
            Locations
          </button>
        </div>

        {/* Suche und Liste */}
        <section className="mt-6 rounded-xl border border-white/10 bg-neutral-950 p-5">
          <div className="flex flex-wrap gap-3">
            <input
              aria-label="Einträge durchsuchen"
              className={`${inputStyle} min-w-0 flex-1`}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Nach Namen suchen ..."
            />

            <button
              type="button"
              onClick={createNew}
              className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black"
            >
              + Neu anlegen
            </button>
          </div>

          <div className="mt-6 divide-y divide-white/10">
            {filteredItems.length === 0 ? (
              <p className="py-6 text-sm text-neutral-500">
                Keine Einträge gefunden.
              </p>
            ) : (
              filteredItems.map((item) => {
                const party =
                  category === "parties"
                    ? parties.find((p) => p.id === item.id)
                    : undefined;

                return (
                  <div
                    key={item.id}
                    className="flex flex-wrap items-center justify-between gap-4 py-4"
                  >
                    <div className="min-w-0">
                      <p className="font-medium">{item.name}</p>

                      <p className="mt-1 text-sm text-neutral-500">
                        {item.instagram
                          ? `@${item.instagram}`
                          : "Instagram unbekannt"}
                      </p>

                      {party && (
                        <div className="mt-2 space-y-1 text-xs text-neutral-400">
                          {party.residentDjIds.length > 0 && (
                            <p>
                              Residents:{" "}
                              {getActorNames(party.residentDjIds)}
                            </p>
                          )}

                          {party.otherDjIds.length > 0 && (
                            <p>
                              Weitere DJs:{" "}
                              {getActorNames(party.otherDjIds)}
                            </p>
                          )}

                          {party.organizerIds.length > 0 && (
                            <p>
                              Veranstalter:{" "}
                              {getActorNames(party.organizerIds)}
                            </p>
                          )}
                        </div>
                      )}
                    </div>

                    <button
                      type="button"
                      onClick={() => editItem(item)}
                      className="rounded-lg border border-white/15 px-4 py-2 text-sm hover:bg-white/10"
                    >
                      Bearbeiten
                    </button>
                  </div>
                );
              })
            )}
          </div>
        </section>

        {/* Editor */}
        {editorOpen && (
          <form
            onSubmit={saveItem}
            className="mt-6 rounded-xl border border-white/20 bg-neutral-950 p-6"
          >
            <h2 className="text-xl font-semibold">
              {editingId ? "Eintrag bearbeiten" : "Neuer Eintrag"}
            </h2>

            {/* Basisdaten */}
            <label className="mt-5 block">
              <span className="mb-2 block text-sm text-neutral-300">
                Name
              </span>

              <input
                className={inputStyle}
                value={name}
                onChange={(event) => setName(event.target.value)}
                required
                maxLength={200}
              />
            </label>

            <label className="mt-5 block">
              <span className="mb-2 block text-sm text-neutral-300">
                Instagram-Handle (optional)
              </span>

              <input
                className={inputStyle}
                value={instagram}
                onChange={(event) => setInstagram(event.target.value)}
                placeholder="username"
              />
            </label>

            {category === "parties" && (
              <>
                {/* DJs */}
                <fieldset className="mt-8 border-t border-white/10 pt-6">
                  <legend className="text-lg font-semibold">
                    DJs / Acts
                  </legend>

                  <p className="mt-2 text-sm text-neutral-400">
                    Resident oder gelegentlich beteiligt?
                    Die tatsächliche Besetzung wird später
                    je Veranstaltung festgelegt.
                  </p>

                  {djActors.length === 0 ? (
                    <p className="mt-4 text-sm text-neutral-500">
                      Noch keine DJs angelegt. Erstelle sie
                      zuerst in der Akteur-Verwaltung.
                    </p>
                  ) : (
                    <div className="mt-4 space-y-3">
                      {djActors.map((actor) => {
                        const assignment: DjAssignment =
                          residentDjIds.includes(actor.id)
                            ? "resident"
                            : otherDjIds.includes(actor.id)
                              ? "other"
                              : "none";

                        return (
                          <label
                            key={actor.id}
                            className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-neutral-900 p-3"
                          >
                            <span>
                              <span className="block text-sm font-medium">
                                {actor.name}
                              </span>

                              {actor.instagram && (
                                <span className="text-xs text-neutral-500">
                                  @{actor.instagram}
                                </span>
                              )}
                            </span>

                            <select
                              aria-label={`Zuordnung von ${actor.name}`}
                              className="rounded-lg border border-white/15 bg-neutral-800 px-3 py-2 text-sm"
                              value={assignment}
                              onChange={(event) =>
                                setDjAssignment(
                                  actor.id,
                                  event.target.value as DjAssignment
                                )
                              }
                            >
                              <option value="none">
                                Nicht zugeordnet
                              </option>
                              <option value="resident">
                                Resident-DJ
                              </option>
                              <option value="other">
                                Weiterer DJ / Act
                              </option>
                            </select>
                          </label>
                        );
                      })}
                    </div>
                  )}
                </fieldset>

                {/* Veranstalter */}
                <fieldset className="mt-8 border-t border-white/10 pt-6">
                  <legend className="text-lg font-semibold">
                    Veranstalter
                  </legend>

                  <p className="mt-2 text-sm text-neutral-400">
                    Mehrfachauswahl möglich, einschließlich
                    Personen und Kollektiven.
                  </p>

                  {organizerActors.length === 0 ? (
                    <p className="mt-4 text-sm text-neutral-500">
                      Noch keine Akteure mit Veranstalterrolle vorhanden.
                    </p>
                  ) : (
                    <div className="mt-4 space-y-3">
                      {organizerActors.map((actor) => (
                        <label
                          key={actor.id}
                          className="flex items-center gap-3 rounded-lg bg-neutral-900 p-3 text-sm"
                        >
                          <input
                            type="checkbox"
                            checked={organizerIds.includes(actor.id)}
                            onChange={() => toggleOrganizer(actor.id)}
                            className="size-4 accent-white"
                          />

                          <span>
                            {actor.name}
                            <span className="ml-2 text-xs text-neutral-500">
                              {actor.kind === "collective"
                                ? "Kollektiv"
                                : "Person"}
                            </span>
                          </span>
                        </label>
                      ))}
                    </div>
                  )}
                </fieldset>

                {/* Vorgabe für Event-Mentions */}
                <div className="mt-8 border-t border-white/10 pt-6">
                  <label className="block">
                    <span className="mb-2 block text-sm font-semibold">
                      DJ-Mentions bei neuen Events
                    </span>

                    <select
                      className={inputStyle}
                      value={djMentionDefault}
                      onChange={(event) =>
                        setDjMentionDefault(
                          event.target.value as DjMentionDefault
                        )
                      }
                    >
                      <option value="manual">
                        Keine DJs vorauswählen
                      </option>

                      <option value="residents">
                        Resident-DJs vorauswählen
                      </option>
                    </select>
                  </label>

                  <p className="mt-2 text-xs text-neutral-500">
                    Die Vorgabe bestimmt später nur die
                    vorausgewählten Checkboxen im Event-Editor.
                    Jeder Haken bleibt individuell änderbar.
                  </p>
                </div>
              </>
            )}

            {error && (
              <p role="alert" className="mt-5 text-sm text-red-400">
                {error}
              </p>
            )}

            <div className="mt-8 flex gap-3">
              <button
                type="submit"
                className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black"
              >
                Übernehmen
              </button>

              <button
                type="button"
                onClick={() => {
                  setEditorOpen(false);
                  resetForm();
                }}
                className="rounded-lg border border-white/15 px-5 py-3 text-sm"
              >
                Abbrechen
              </button>
            </div>
          </form>
        )}

        <p className="mt-6 text-xs text-neutral-500">
          Entwicklungsstand: Änderungen werden noch nicht
          dauerhaft gespeichert.
        </p>
      </div>
    </main>
  );
}
