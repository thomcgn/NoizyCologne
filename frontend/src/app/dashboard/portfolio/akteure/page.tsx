
"use client";

import { useState, type FormEvent } from "react";
import {
  useCatalog,
  type Actor,
  type ActorKind,
  type ActorRole,
} from "../../CatalogProvider";
import DashboardModal from "../../DashboardModal";
import ActionFeedback, { type ActionFeedbackKind } from "../../ActionFeedback";
import { useDirtyGuard } from "../../useDirtyGuard";
import DataCollection, { type CollectionColumn } from "../../DataCollection";
import { useWorkspaceAction } from "../../workspaceActions";

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
    deleteActor,
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
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState("");
  const [search, setSearch] = useState("");
  const [saveFeedback, setSaveFeedback] = useState<{ kind: ActionFeedbackKind; message: string } | null>(null);

  const editorSignature = JSON.stringify({ editingId, name, kind, roles, instagram, collectiveIds });
  const dirtyGuard = useDirtyGuard(
    editorSignature,
    editorOpen ? editingId ?? "new-actor" : "actor-editor-closed",
  );

  const collectives = actors.filter(
    (actor) => actor.kind === "collective"
  );
  const [kindFilter, setKindFilter] = useState("all");
  const [roleFilter, setRoleFilter] = useState("all");
  const filteredActors = actors.filter(actor => (kindFilter === "all" || actor.kind === kindFilter) && (roleFilter === "all" || actor.roles.some(role => role === roleFilter)));
  const collectiveNames = (actor: Actor) => actor.collectiveIds.map(id => collectives.find(collective => collective.id === id)?.name ?? "").filter(Boolean).join(", ");
  const roleNames = (actor: Actor) => actor.roles.map(role => role === "dj" ? "DJ / Act" : "Veranstalter").join(", ");

  function createNew() {
    setEditingId(null);
    setName("");
    setKind("person");
    setRoles([]);
    setInstagram("");
    setCollectiveIds([]);
    setError("");
    setSaveFeedback(null);
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
    setSaveFeedback(null);
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
    dirtyGuard.setClean();
    setSaveFeedback({ kind: "success", message: `Akteur „${cleanName}“ gespeichert.` });
  }

  async function removeActor(actor: Actor) {
    if (!window.confirm(`„${actor.name}“ wirklich löschen? Verwendete Zuordnungen werden geschützt.`)) return;
    setDeleteError("");
    setDeletingId(actor.id);
    try {
      await deleteActor(actor.id);
    } catch (cause) {
      setDeleteError(cause instanceof Error ? cause.message : "Der Akteur konnte nicht gelöscht werden.");
    } finally {
      setDeletingId(null);
    }
  }

  useWorkspaceAction("Akteur anlegen", createNew, saving);
  const columns: CollectionColumn<Actor>[] = [
    {key: "name", label: "Name", sortValue: actor => actor.name, render: actor => <strong>{actor.name}</strong>},
    {key: "kind", label: "Art", sortValue: actor => actor.kind, render: actor => actor.kind === "collective" ? "Kollektiv" : "Person"},
    {key: "roles", label: "Rollen", render: actor => roleNames(actor) || "Keine Rolle"},
    {key: "instagram", label: "Instagram", sortValue: actor => actor.instagram, render: actor => actor.instagram ? `@${actor.instagram}` : "Noch offen"},
    {key: "collectives", label: "Mitglied bei", render: actor => collectiveNames(actor) || "Keine Zuordnung"},
  ];

  return (
    <main className="app-page min-h-dvh bg-black px-4 py-6 text-white sm:py-8 md:px-10">
      <div className="mx-auto max-w-5xl">
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
            className="cc-button cc-button-primary cc-page-primary"
          >
            + Neu anlegen
          </button>
        </header>

        {deleteError && (
          <p role="alert" className="mt-5 rounded-lg border border-red-400/40 bg-red-950/30 p-3 text-sm text-red-300">
            {deleteError}
          </p>
        )}
        {saveFeedback && <ActionFeedback kind={saveFeedback.kind}>{saveFeedback.message}</ActionFeedback>}

        <section className="cc-catalog-list" aria-label="Akteure">
          {actorsLoading ? <p className="cc-empty" role="status">Akteure werden geladen …</p>
          : actorsError ? <div className="cc-message" role="alert"><p>{actorsError}</p><button type="button" className="cc-button" onClick={() => void reloadActors()}>Erneut versuchen</button></div>
          : <DataCollection items={filteredActors} columns={columns} label="Akteure" storageKey="noizy-view-actors" getName={actor => actor.name} onOpen={editActor} openDisabled={saving || deletingId !== null} query={search} onQueryChange={setSearch} searchLabel="DJs und Kollektive durchsuchen" searchText={actor => [actor.name, actor.instagram, actor.kind === "collective" ? "Kollektiv" : "Person", roleNames(actor), collectiveNames(actor)].join(" ")} empty={actors.length ? "Keine passenden DJs oder Kollektive gefunden." : "Noch keine DJs oder Kollektive erfasst."} selectedId={editorOpen ? editingId : null} toolbar={<><label className="cc-inline-filter">Art<select className="cc-input" value={kindFilter} onChange={event => setKindFilter(event.target.value)}><option value="all">Alle</option><option value="person">Personen</option><option value="collective">Kollektive</option></select></label><label className="cc-inline-filter">Rolle<select className="cc-input" value={roleFilter} onChange={event => setRoleFilter(event.target.value)}><option value="all">Alle</option><option value="dj">DJ / Act</option><option value="organizer">Veranstalter</option></select></label></>} actions={actor => <div className="cc-inline-actions"><button type="button" className="cc-button" disabled={saving || deletingId !== null} onClick={() => editActor(actor)}>Bearbeiten</button><button type="button" className="cc-button cc-button-danger" disabled={deletingId !== null} onClick={() => void removeActor(actor)}>{deletingId === actor.id ? "Löschen ..." : "Löschen"}</button></div>} />}
        </section>

        {/* Akteur-Editor */}
        <DashboardModal variant="drawer" protectChanges dirtyGuard={dirtyGuard}
          open={editorOpen}
          onClose={() => setEditorOpen(false)}
          title={editingId ? "Akteur bearbeiten" : "Neuer Akteur"}
          description="Personen, Kollektive, Rollen und Instagram-Handles verwalten."
        >
          <form
            onSubmit={saveActor}
            className="space-y-1"
          >
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
                {saving ? "Speichern ..." : "Speichern und schließen"}
              </button>

              <button
                type="button"
                data-modal-dismiss
                className="rounded-lg border border-white/15 px-5 py-3 text-sm"
              >
                Abbrechen
              </button>
            </div>
          </form>
        </DashboardModal>

        <p className="mt-6 text-xs text-neutral-500">
          Akteure werden dauerhaft in PostgreSQL gespeichert.
        </p>
      </div>
    </main>
  );
}
