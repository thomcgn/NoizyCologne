
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
  const {
    actors,
    actorsLoading,
    actorsError,
    reloadActors,
    createActor,
    updateActor,
  } = useCatalog();

  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  const [name, setName] = useState("");
  const [kind, setKind] = useState<ActorKind>("person");
  const [roles, setRoles] = useState<ActorRole[]>([]);
  const [instagram, setInstagram] = useState("");
  const [collectiveIds, setCollectiveIds] = useState<string[]>([]);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

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

  async function saveActor(event: FormEvent<HTMLFormElement>) {
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

    const actorData = {
      name: cleanName,
      kind,
      roles: [...roles],
      instagram: cleanInstagram,
      collectiveIds: kind === "person" ? collectiveIds : [],
    };

    setSaving(true);
    setError("");

    try {
      if (editingId) {
        await updateActor({ id: editingId, ...actorData });
      } else {
        await createActor(actorData);
      }
    } catch (cause) {
      setError(
        cause instanceof Error
          ? `Akteur konnte nicht gespeichert werden: ${cause.message}`
          : "Akteur konnte wegen eines unbekannten Netzwerkfehlers nicht gespeichert werden."
      );
      setSaving(false);
      return;
    }

    setSaving(false);
    setEditorOpen(false);
    setEditingId(null);
    setError("");
  }

  return (
    <main className="app-page min-h-dvh bg-black px-4 py-6 text-white sm:py-8 md:px-10">
      <div className="mx-auto max-w-5xl">
        <Link
          href="/dashboard/djs"
          className="text-sm text-neutral-400 hover:text-white"
        >
          ← DJs
        </Link>

        <header className="mt-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
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
            className="min-h-12 rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black"
          >
            + Akteur anlegen
          </button>
        </header>

        {/* Akteursliste */}
        <section className="mt-8 rounded-xl border border-white/10 bg-neutral-950 p-5">
          {actorsLoading ? (
            <p className="py-6 text-center text-sm text-neutral-400" role="status">
              Akteure werden aus PostgreSQL geladen ...
            </p>
          ) : actorsError ? (
            <div className="py-6 text-sm" role="alert">
              <p className="text-red-400">{actorsError}</p>
              <button
                type="button"
                onClick={() => void reloadActors()}
                className="mt-3 rounded-lg border border-white/20 px-4 py-2 text-white hover:bg-white/10"
              >
                Erneut versuchen
              </button>
            </div>
          ) : actors.length === 0 ? (
            <p className="py-6 text-center text-sm text-neutral-500">
              Noch keine DJs oder Kollektive erfasst.
            </p>
          ) : (
            <div className="divide-y divide-white/10">
              {actors.map((actor) => (
                <div
                  key={actor.id}
                  className="flex flex-col items-stretch gap-3 py-4 sm:flex-row sm:items-center sm:justify-between"
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
                    className="min-h-11 rounded-lg border border-white/15 px-4 py-2 text-sm hover:bg-white/10"
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
            className="mt-6 rounded-xl border border-white/20 bg-neutral-950 p-4 sm:p-6"
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

            <div className="mt-6 grid grid-cols-2 gap-3 sm:flex">
              <button
                type="submit"
                disabled={saving}
                className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black disabled:cursor-not-allowed disabled:opacity-60"
              >
                {saving ? "Speichern ..." : "Übernehmen"}
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
          Akteure werden dauerhaft in PostgreSQL gespeichert.
        </p>
      </div>
    </main>
  );
}
