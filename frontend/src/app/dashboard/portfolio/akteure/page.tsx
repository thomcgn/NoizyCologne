
"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import {
  useCatalog,
  type Actor,
  type ActorKind,
  type ActorRole,
} from "../../CatalogProvider";

const inputStyle =
  "w-full rounded-lg border border-white/15 bg-neutral-900 " +
  "px-3 py-2.5 text-sm text-white outline-none " +
  "focus:border-white/60";

export default function ActorsPage() {
  const { actors, setActors } = useCatalog();

  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  const [name, setName] = useState("");
  const [kind, setKind] = useState<ActorKind>("person");
  const [roles, setRoles] = useState<ActorRole[]>([]);
  const [instagram, setInstagram] = useState("");
  const [collectiveIds, setCollectiveIds] = useState<string[]>([]);
  const [error, setError] = useState("");

  const collectives = actors.filter(
    (actor) => actor.kind === "collective"
  );

  function createNew() {
    setEditingId(null);
    setName("");
    setKind("person");
    setRoles([]);
    setInstagram("");
    setCollectiveIds([]);
    setError("");
    setEditorOpen(true);
  }

  function editActor(actor: Actor) {
    setEditingId(actor.id);
    setName(actor.name);
    setKind(actor.kind);
    setRoles([...actor.roles]);
    setInstagram(actor.instagram);
    setCollectiveIds([...actor.collectiveIds]);
    setError("");
    setEditorOpen(true);
  }

  function toggleRole(role: ActorRole) {
    setRoles((current) =>
      current.includes(role)
        ? current.filter((item) => item !== role)
        : [...current, role]
    );
  }

  function toggleCollective(id: string) {
    setCollectiveIds((current) =>
      current.includes(id)
        ? current.filter((item) => item !== id)
        : [...current, id]
    );
  }

  function saveActor(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const cleanName = name.trim();
    const cleanInstagram = instagram.trim().replace(/^@/, "");

    if (!cleanName) {
      setError("Bitte einen Namen eingeben.");
      return;
    }

    const duplicate = actors.some(
      (actor) =>
        actor.id !== editingId &&
        actor.name.toLocaleLowerCase("de-DE") ===
          cleanName.toLocaleLowerCase("de-DE")
    );

    if (duplicate) {
      setError("Dieser Akteur existiert bereits.");
      return;
    }

    if (
      cleanInstagram &&
      !/^[A-Za-z0-9._]{1,30}$/.test(cleanInstagram)
    ) {
      setError("Bitte einen gültigen Instagram-Handle eingeben.");
      return;
    }

    const updatedActor: Actor = {
      id: editingId ?? crypto.randomUUID(),
      name: cleanName,
      kind,
      roles,
      instagram: cleanInstagram,
      collectiveIds: kind === "person" ? collectiveIds : [],
    };

    setActors((current) =>
      editingId
        ? current.map((actor) =>
            actor.id === editingId ? updatedActor : actor
          )
        : [...current, updatedActor]
    );

    setEditorOpen(false);
    setEditingId(null);
    setError("");
  }

  return (
    <main className="min-h-dvh bg-black px-4 py-8 text-white md:px-10">
      <div className="mx-auto max-w-5xl">
        <Link
          href="/dashboard/portfolio"
          className="text-sm text-neutral-400 hover:text-white"
        >
          ← Party-Portfolio
        </Link>

        <header className="mt-6 flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-xs uppercase tracking-[0.2em] text-neutral-500">
              NoizyCologne / Stammdaten
            </p>

            <h1 className="mt-2 text-3xl font-bold">
              DJs & Kollektive
            </h1>

            <p className="mt-3 text-neutral-400">
              Personen, Kollektive und ihre Rollen verwalten.
            </p>
          </div>

          <button
            type="button"
            onClick={createNew}
            className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black"
          >
            + Akteur anlegen
          </button>
        </header>

        {/* Akteursliste */}
        <section className="mt-8 rounded-xl border border-white/10 bg-neutral-950 p-5">
          {actors.length === 0 ? (
            <p className="py-6 text-center text-sm text-neutral-500">
              Noch keine DJs oder Kollektive erfasst.
            </p>
          ) : (
            <div className="divide-y divide-white/10">
              {actors.map((actor) => (
                <div
                  key={actor.id}
                  className="flex flex-wrap items-center justify-between gap-4 py-4"
                >
                  <div>
                    <p className="font-semibold">{actor.name}</p>

                    <p className="mt-1 text-sm text-neutral-400">
                      {actor.kind === "collective"
                        ? "Kollektiv"
                        : "Person"}
                      {actor.roles.length > 0 &&
                        ` · ${actor.roles
                          .map((role) =>
                            role === "dj" ? "DJ / Act" : "Veranstalter"
                          )
                          .join(", ")}`}
                    </p>

                    {actor.instagram && (
                      <p className="mt-1 text-sm text-neutral-500">
                        @{actor.instagram}
                      </p>
                    )}

                    {actor.collectiveIds.length > 0 && (
                      <p className="mt-1 text-xs text-neutral-500">
                        Mitglied bei:{" "}
                        {actor.collectiveIds
                          .map(
                            (id) =>
                              collectives.find((c) => c.id === id)?.name
                          )
                          .filter(Boolean)
                          .join(", ")}
                      </p>
                    )}
                  </div>

                  <button
                    type="button"
                    onClick={() => editActor(actor)}
                    className="rounded-lg border border-white/15 px-4 py-2 text-sm hover:bg-white/10"
                  >
                    Bearbeiten
                  </button>
                </div>
              ))}
            </div>
          )}
        </section>

        {/* Akteur-Editor */}
        {editorOpen && (
          <form
            onSubmit={saveActor}
            className="mt-6 rounded-xl border border-white/20 bg-neutral-950 p-6"
          >
            <h2 className="text-xl font-semibold">
              {editingId ? "Akteur bearbeiten" : "Neuer Akteur"}
            </h2>

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
                placeholder="Name des DJs oder Kollektivs"
              />
            </label>

            <label className="mt-5 block">
              <span className="mb-2 block text-sm text-neutral-300">
                Typ
              </span>
              <select
                className={inputStyle}
                value={kind}
                onChange={(event) =>
                  setKind(event.target.value as ActorKind)
                }
              >
                <option value="person">Person</option>
                <option value="collective">Kollektiv</option>
              </select>
            </label>

            <fieldset className="mt-5">
              <legend className="mb-3 text-sm text-neutral-300">
                Rollen (Mehrfachauswahl)
              </legend>

              <div className="space-y-3">
                <label className="flex items-center gap-3 text-sm">
                  <input
                    type="checkbox"
                    checked={roles.includes("dj")}
                    onChange={() => toggleRole("dj")}
                    className="size-4 accent-white"
                  />
                  DJ / Act
                </label>

                <label className="flex items-center gap-3 text-sm">
                  <input
                    type="checkbox"
                    checked={roles.includes("organizer")}
                    onChange={() => toggleRole("organizer")}
                    className="size-4 accent-white"
                  />
                  Veranstalter
                </label>
              </div>
            </fieldset>

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

            {/* Mitgliedschaft */}
            {kind === "person" && collectives.length > 0 && (
              <fieldset className="mt-5">
                <legend className="mb-3 text-sm text-neutral-300">
                  Mitglied in Kollektiven
                </legend>

                <div className="space-y-3">
                  {collectives.map((collective) => (
                    <label
                      key={collective.id}
                      className="flex items-center gap-3 text-sm"
                    >
                      <input
                        type="checkbox"
                        checked={collectiveIds.includes(collective.id)}
                        onChange={() => toggleCollective(collective.id)}
                        className="size-4 accent-white"
                      />
                      {collective.name}
                    </label>
                  ))}
                </div>
              </fieldset>
            )}

            {error && (
              <p role="alert" className="mt-5 text-sm text-red-400">
                {error}
              </p>
            )}

            <div className="mt-6 flex gap-3">
              <button
                type="submit"
                className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black"
              >
                Übernehmen
              </button>

              <button
                type="button"
                onClick={() => setEditorOpen(false)}
                className="rounded-lg border border-white/15 px-5 py-3 text-sm"
              >
                Abbrechen
              </button>
            </div>
          </form>
        )}

        <p className="mt-6 text-xs text-neutral-500">
          Entwicklungsstand: Änderungen bleiben nur während der
          aktuellen Sitzung erhalten. Keine Datenbankspeicherung.
        </p>
      </div>
    </main>
  );
}
