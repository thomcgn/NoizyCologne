"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";

import {
  useCatalog,
  PARTY_COLORS,
  type CatalogItem,
  type PartySeries,
  type DjMentionDefault,
  type RecurrenceRule,
} from "../CatalogProvider";

export type Category = "parties" | "locations";
type DjAssignment = "none" | "resident" | "other";
type RecurrenceChoice = "none" | "weekly" | "monthly_weekday" | "other";

const weekdayNames = [
  "Sonntag",
  "Montag",
  "Dienstag",
  "Mittwoch",
  "Donnerstag",
  "Freitag",
  "Samstag",
];

function describeRecurrence(rule: RecurrenceRule | null): string {
  if (!rule) return "Kein fester Turnus";
  if (rule.kind === "weekly") {
    return `Jeden ${weekdayNames[rule.weekday]}`;
  }
  if (rule.kind === "monthly_weekday") {
    const occurrence = rule.occurrence === -1 ? "Letzter" : `${rule.occurrence}.`;
    return `${occurrence} ${weekdayNames[rule.weekday]} im Monat`;
  }
  return `Turnus unklar: ${rule.description}`;
}

const inputStyle =
  "w-full rounded-lg border border-white/15 bg-neutral-900 " +
  "px-3 py-2.5 text-sm text-white outline-none " +
  "focus:border-white/60";

export default function PortfolioPage({
  initialCategory = "parties",
  standalone = false,
}: {
  initialCategory?: Category;
  standalone?: boolean;
}) {
  const {
    parties,
    partiesLoading,
    partiesError,
    reloadParties,
    createParty,
    updateParty,
    uploadPartyFlyer,
    deletePartyFlyer,
    venues,
    venuesLoading,
    venuesError,
    reloadVenues,
    createVenue,
    updateVenue,
    actors,
  } = useCatalog();

  const [category, setCategory] = useState<Category>(initialCategory);
  const [search, setSearch] = useState("");
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  const [name, setName] = useState("");
  const [instagram, setInstagram] = useState("");
  const [color, setColor] = useState<string>(PARTY_COLORS[0]);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const [flyerFile, setFlyerFile] = useState<File | null>(null);
  const [removeFlyer, setRemoveFlyer] = useState(false);

  // Beziehungen einer Partyreihe
  const [residentDjIds, setResidentDjIds] = useState<string[]>([]);
  const [otherDjIds, setOtherDjIds] = useState<string[]>([]);
  const [organizerIds, setOrganizerIds] = useState<string[]>([]);

  const [djMentionDefault, setDjMentionDefault] =
    useState<DjMentionDefault>("manual");


  // Optionaler Turnus: Mo=1 ... Sa=6, So=0 (wie Date.getDay())
  const [recurrenceKind, setRecurrenceKind] =
    useState<RecurrenceChoice>("none");
  const [weekday, setWeekday] = useState(5);
  const [occurrence, setOccurrence] = useState(3);
  const [recurrenceNote, setRecurrenceNote] = useState("");


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
    setRecurrenceKind("none");
    setWeekday(5);
    setOccurrence(3);
    setRecurrenceNote("");
    setEditingId(null);
    setName("");
    setInstagram("");
    setResidentDjIds([]);
    setOtherDjIds([]);
    setOrganizerIds([]);
    setDjMentionDefault("manual");
    setColor(PARTY_COLORS[parties.length % PARTY_COLORS.length]);
    setFlyerFile(null);
    setRemoveFlyer(false);
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
        setColor(party.color);
        setResidentDjIds([...party.residentDjIds]);
        setOtherDjIds([...party.otherDjIds]);
        setOrganizerIds([...party.organizerIds]);
        setDjMentionDefault(party.djMentionDefault);

        const rule = party.recurrence;
        setRecurrenceKind(rule?.kind ?? "none");

        if (rule?.kind === "weekly" || rule?.kind === "monthly_weekday") {
          setWeekday(rule.weekday);
        }
        if (rule?.kind === "monthly_weekday") {
          setOccurrence(rule.occurrence);
        }
        setRecurrenceNote(rule?.kind === "other" ? rule.description : "");
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

  async function saveItem(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");

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

    // Eine unklare Regel bleibt nur ein Hinweis: Sie erzeugt keine Termine.
    let recurrence: RecurrenceRule | null = null;
    if (category === "parties") {
      if (recurrenceKind === "weekly") {
        recurrence = { kind: "weekly", weekday };
      } else if (recurrenceKind === "monthly_weekday") {
        recurrence = { kind: "monthly_weekday", weekday, occurrence };
      } else if (recurrenceKind === "other") {
        const description = recurrenceNote.trim();
        if (!description) {
          setError("Bitte den unklaren Turnus beschreiben.");
          return;
        }
        recurrence = { kind: "other", description };
      }
    }

    if (category === "parties") {
      const partyData: Omit<PartySeries, "id"> = {
        name: cleanName,
        instagram: cleanInstagram,
        residentDjIds: [...residentDjIds],
        otherDjIds: [...otherDjIds],
        organizerIds: [...organizerIds],
        djMentionDefault,
        color,
        recurrence,
        defaultFlyer: parties.find((party) => party.id === editingId)?.defaultFlyer ?? null,
      };

      setSaving(true);
      try {
        let savedParty: PartySeries;
        if (editingId) {
          savedParty = await updateParty({ id: editingId, ...partyData });
        } else {
          savedParty = await createParty(partyData);
          // Bei einem nachgelagerten Flyer-Fehler muss ein erneuter Speicherversuch
          // die gerade angelegte Partyreihe aktualisieren statt ein Duplikat anzulegen.
          setEditingId(savedParty.id);
        }
        if (flyerFile) {
          await uploadPartyFlyer(savedParty.id, flyerFile);
        } else if (removeFlyer && savedParty.defaultFlyer) {
          await deletePartyFlyer(savedParty.id);
        }
      } catch (cause) {
        setError(
          cause instanceof Error
            ? `Partyreihe konnte nicht gespeichert werden: ${cause.message}`
            : "Partyreihe konnte wegen eines unbekannten Netzwerkfehlers nicht gespeichert werden."
        );
        setSaving(false);
        return;
      }
      setSaving(false);
    } else {
      const updatedVenue: CatalogItem = {
        id: editingId ?? "",
        name: cleanName,
        instagram: cleanInstagram,
      };

      setSaving(true);
      try {
        if (editingId) {
          await updateVenue(updatedVenue);
        } else {
          await createVenue({
            name: updatedVenue.name,
            instagram: updatedVenue.instagram,
          });
        }
      } catch (cause) {
        setError(
          cause instanceof Error
            ? `Location konnte nicht gespeichert werden: ${cause.message}`
            : "Location konnte wegen eines unbekannten Netzwerkfehlers nicht gespeichert werden."
        );
        setSaving(false);
        return;
      }
      setSaving(false);
    }

    setEditorOpen(false);
    resetForm();
  }

  return (
    <main className="app-page min-h-dvh bg-black px-4 py-6 text-white sm:py-8 md:px-10">
      <div className="mx-auto max-w-6xl">
        <header className="mt-6">
          <p className="text-xs uppercase tracking-[0.2em] text-neutral-500">
            NoizyCologne / Stammdaten
          </p>

          <h1 className="mt-2 text-3xl font-bold">
            {category === "parties" ? "Partys" : "Locations"}
          </h1>

          <p className="mt-3 text-neutral-400">
            {category === "parties"
              ? "Partyreihen, Farben, Turnus und Beteiligte verwalten."
              : "Locations und ihre Instagram-Accounts verwalten."}
          </p>
        </header>

        {category === "parties" && !standalone && (
          <Link
            href="/dashboard/portfolio/akteure"
            className="mt-5 inline-flex min-h-12 items-center rounded-lg border border-white/20 px-5 py-3 text-sm hover:bg-white/10"
          >
            DJs, Kollektive & Veranstalter verwalten →
          </Link>
        )}

        {/* Kategorien */}
        {!standalone && (
          <div className="mt-8 grid grid-cols-2 gap-2 sm:flex sm:flex-wrap">
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
        )}

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
            {category === "parties" && partiesLoading ? (
              <p className="py-6 text-sm text-neutral-400" role="status">
                Partyreihen werden aus PostgreSQL geladen ...
              </p>
            ) : category === "parties" && partiesError ? (
              <div className="py-6 text-sm" role="alert">
                <p className="text-red-400">{partiesError}</p>
                <button
                  type="button"
                  onClick={() => void reloadParties()}
                  className="mt-3 rounded-lg border border-white/20 px-4 py-2 text-white hover:bg-white/10"
                >
                  Erneut versuchen
                </button>
              </div>
            ) : category === "locations" && venuesLoading ? (
              <p className="py-6 text-sm text-neutral-400" role="status">
                Locations werden aus PostgreSQL geladen ...
              </p>
            ) : category === "locations" && venuesError ? (
              <div className="py-6 text-sm" role="alert">
                <p className="text-red-400">{venuesError}</p>
                <button
                  type="button"
                  onClick={() => void reloadVenues()}
                  className="mt-3 rounded-lg border border-white/20 px-4 py-2 text-white hover:bg-white/10"
                >
                  Erneut versuchen
                </button>
              </div>
            ) : filteredItems.length === 0 ? (
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
                    className="flex flex-col items-stretch gap-3 py-4 sm:flex-row sm:items-center sm:justify-between"
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
                          <p>Turnus: {describeRecurrence(party.recurrence)}</p>
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
                      className="min-h-11 rounded-lg border border-white/15 px-4 py-2 text-sm hover:bg-white/10"
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
            className="mt-6 rounded-xl border border-white/20 bg-neutral-950 p-4 sm:p-6"
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
                <section className="mt-8 border-t border-white/10 pt-6">
                  <h3 className="text-lg font-semibold">Standardflyer</h3>
                  <p className="mt-1 text-sm text-neutral-400">
                    Optionales JPEG- oder PNG-Bild für diese Partyreihe, maximal 10 MB.
                    Es wird noch nicht veröffentlicht.
                  </p>
                  {(() => {
                    const currentFlyer = parties.find((party) => party.id === editingId)?.defaultFlyer;
                    return currentFlyer && !removeFlyer && !flyerFile ? (
                      <div className="mt-4 flex flex-wrap items-start gap-4">
                        {/* Flyer wird über die geschützte Same-Origin-API geladen; Next/Image würde die Session nicht automatisch weiterreichen. */}
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img
                          src={currentFlyer.url}
                          alt={`Standardflyer ${name}`}
                          className="h-32 w-24 rounded-lg border border-white/10 object-cover"
                        />
                        <div className="text-sm">
                          <p className="text-neutral-300">{currentFlyer.originalFilename}</p>
                          <button
                            type="button"
                            onClick={() => setRemoveFlyer(true)}
                            className="mt-3 rounded-lg border border-red-400/40 px-3 py-2 text-red-300 hover:bg-red-950/30"
                          >
                            Standardflyer entfernen
                          </button>
                        </div>
                      </div>
                    ) : null;
                  })()}
                  <label className="mt-4 block">
                    <span className="mb-2 block text-sm text-neutral-300">
                      {flyerFile ? "Neuen Standardflyer ausgewählt" : "Bild auswählen"}
                    </span>
                    <input
                      className="block w-full text-sm text-neutral-300 file:mr-4 file:rounded-lg file:border-0 file:bg-white file:px-4 file:py-2 file:font-semibold file:text-black"
                      type="file"
                      accept=".jpg,.jpeg,.png,image/jpeg,image/png"
                      onChange={(event) => {
                        const file = event.target.files?.[0] ?? null;
                        if (!file) return;
                        const filename = file.name.toLowerCase();
                        const isJpeg = file.type === "image/jpeg" || filename.endsWith(".jpg") || filename.endsWith(".jpeg");
                        const isPng = file.type === "image/png" || filename.endsWith(".png");
                        if (!isJpeg && !isPng) {
                          setError("Bitte einen JPEG- oder PNG-Flyer auswählen.");
                          event.target.value = "";
                          return;
                        }
                        if (file.size > 10 * 1024 * 1024) {
                          setError("Der Flyer darf höchstens 10 MB groß sein.");
                          event.target.value = "";
                          return;
                        }
                        setError("");
                        setFlyerFile(file);
                        setRemoveFlyer(false);
                      }}
                    />
                  </label>
                  {flyerFile && (
                    <p className="mt-2 text-xs text-neutral-400">
                      {flyerFile.name} wird beim Speichern hochgeladen und ersetzt den bisherigen Standardflyer.
                    </p>
                  )}
                  {removeFlyer && !flyerFile && (
                    <p className="mt-2 text-xs text-red-300">
                      Der Standardflyer wird beim Speichern entfernt.
                    </p>
                  )}
                </section>

                {/* Farbe der Partyreihe */}
                <label className="mt-5 flex items-center gap-4">
                  <input
                    type="color"
                    value={color}
                    onChange={(event) => setColor(event.target.value)}
                    className="h-11 w-16 cursor-pointer rounded border border-white/20 bg-neutral-900 p-1"
                    aria-label="Kalenderfarbe wählen"
                  />
                  <span>
                    <span className="block text-sm font-medium">Kalenderfarbe</span>
                    <span className="text-xs text-neutral-500">
                      Farbe dieser Partyreihe im Kalender
                    </span>
                  </span>
                </label>

                {/* Optionaler Turnus */}
                <div className="mt-6 rounded-xl border border-white/10 p-5">
                  <h3 className="text-lg font-semibold">Turnus</h3>
                  <p className="mt-1 text-sm text-neutral-400">
                    Optional. Feste Regeln können später Kalenderentwürfe erzeugen.
                  </p>

                  <label className="mt-4 block">
                    <span className="mb-2 block text-sm text-neutral-300">
                      Wiederholung
                    </span>
                    <select
                      className={inputStyle}
                      value={recurrenceKind}
                      onChange={(event) =>
                        setRecurrenceKind(event.target.value as RecurrenceChoice)
                      }
                    >
                      <option value="none">Kein fester Turnus</option>
                      <option value="weekly">Wöchentlich</option>
                      <option value="monthly_weekday">
                        Monatlich nach Wochentag
                      </option>
                      <option value="other">Andere / noch unklare Regel</option>
                    </select>
                  </label>

                  {(recurrenceKind === "weekly" ||
                    recurrenceKind === "monthly_weekday") && (
                    <div className="mt-4 grid gap-4 sm:grid-cols-2">
                      {recurrenceKind === "monthly_weekday" && (
                        <label className="block">
                          <span className="mb-2 block text-sm text-neutral-300">
                            Woche im Monat
                          </span>
                          <select
                            className={inputStyle}
                            value={occurrence}
                            onChange={(event) =>
                              setOccurrence(Number(event.target.value))
                            }
                          >
                            <option value={1}>1. Woche</option>
                            <option value={2}>2. Woche</option>
                            <option value={3}>3. Woche</option>
                            <option value={4}>4. Woche</option>
                            <option value={-1}>Letzte Woche</option>
                          </select>
                        </label>
                      )}

                      <label className="block">
                        <span className="mb-2 block text-sm text-neutral-300">
                          Wochentag
                        </span>
                        <select
                          className={inputStyle}
                          value={weekday}
                          onChange={(event) =>
                            setWeekday(Number(event.target.value))
                          }
                        >
                          <option value={1}>Montag</option>
                          <option value={2}>Dienstag</option>
                          <option value={3}>Mittwoch</option>
                          <option value={4}>Donnerstag</option>
                          <option value={5}>Freitag</option>
                          <option value={6}>Samstag</option>
                          <option value={0}>Sonntag</option>
                        </select>
                      </label>
                    </div>
                  )}

                  {recurrenceKind === "other" && (
                    <label className="mt-4 block">
                      <span className="mb-2 block text-sm text-neutral-300">
                        Regel beschreiben
                      </span>
                      <input
                        className={inputStyle}
                        value={recurrenceNote}
                        onChange={(event) => setRecurrenceNote(event.target.value)}
                        placeholder="z. B. ungefähr alle zwei Monate"
                      />
                      <span className="mt-2 block text-xs text-neutral-500">
                        Eine unklare Regel erzeugt keine automatischen Termine.
                      </span>
                    </label>
                  )}
                </div>
              </>
            )}

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

            <div className="mt-8 grid grid-cols-2 gap-3 sm:flex">
              <button
                type="submit"
                disabled={saving}
                className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black"
              >
                {saving ? "Speichern ..." : "Übernehmen"}
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

      </div>
    </main>
  );
}
