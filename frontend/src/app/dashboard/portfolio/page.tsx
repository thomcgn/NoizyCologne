"use client";

import { discoveryStatusLabel } from "../discoveryLabels";
import Link from "next/link";
import { useEffect, useRef, useState, type FormEvent } from "react";

import {
  useCatalog,
  PARTY_COLORS,
  type CatalogItem,
  type PartySeries,
  type DjMentionDefault,
  type RecurrenceRule,
} from "../CatalogProvider";
import DashboardModal from "../DashboardModal";
import DataCollection, { type CollectionColumn } from "../DataCollection";
import { useWorkspaceAction } from "../workspaceActions";
import SourceOverview, {type DiscoverySource} from "../SourceOverview";

export type Category = "parties" | "locations";
type DjAssignment = "none" | "resident" | "other";
type RecurrenceChoice = "none" | "weekly" | "monthly_weekday" | "other";
type WebsiteReport = { previewToken: string; websiteUrl: string; suggestedName: string; checkedAt: string; eventCount: number; partyCount: number; knownSeriesCount: number; incomplete: boolean; problems: string[]; sources: { url: string; fetchMethod: string; diagnostics: string[]; extraction: { status: string; events: unknown[]; complete: boolean } }[] };
type ActiveGenre = { id: string; displayName: string; active: boolean };

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
    deleteParty,
    uploadPartyFlyer,
    deletePartyFlyer,
    venues,
    venuesLoading,
    venuesError,
    reloadVenues,
    createVenue,
    updateVenue,
    deleteVenue,
    actors,
  } = useCatalog();

  const [category, setCategory] = useState<Category>(initialCategory);
  const [search, setSearch] = useState("");
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  const [name, setName] = useState("");
  const [instagram, setInstagram] = useState("");
  const [websiteUrl, setWebsiteUrl] = useState("");
  const [eventsUrl, setEventsUrl] = useState("");
  const [discoveryEnabled, setDiscoveryEnabled] = useState(false);
  const [probeResult, setProbeResult] = useState("");
  const [probing, setProbing] = useState(false);
  const [locationSources,setLocationSources] = useState<DiscoverySource[]>([]);
  const [sourceNotice,setSourceNotice] = useState("");
  const [websiteReport, setWebsiteReport] = useState<WebsiteReport | null>(null);
  const probeSequence = useRef(0);
  function changeWebsite(value: string) {
    probeSequence.current++; setWebsiteUrl(value); setWebsiteReport(null); setProbeResult(""); setProbing(false);
  }
  async function probeSource() {
    const sequence = ++probeSequence.current;
    setProbing(true); setProbeResult(""); setWebsiteReport(null);
    try {
      const response = await fetch("/api/v1/discovery/onboard", {method: "POST", headers: {"Content-Type":"application/json"}, body: JSON.stringify({url: websiteUrl.trim()})});
      if (!response.ok) throw new Error(`Website-Prüfung: HTTP ${response.status}`);
      const report: WebsiteReport = await response.json();
      if (sequence !== probeSequence.current) return;
      setWebsiteReport(report);
      setName(current => current.trim() ? current : report.suggestedName.slice(0, 200));
      const countText = report.eventCount === 0 && report.problems.length ? "Keine verlässliche Veranstaltungszahl ermittelbar." : `${report.eventCount} Veranstaltungen gefunden, ${report.partyCount} mögliche Partys, ${report.knownSeriesCount} bekannte Partyreihen.`;
      setProbeResult(`${countText}\nGeprüft: ${new Date(report.checkedAt).toLocaleString("de-DE")}\n${report.sources.map(source => `${source.fetchMethod === "BROWSER" ? "Browser" : "HTTP"}: ${discoveryStatusLabel(source.extraction.status)} (${source.url})`).join("\n")}${report.incomplete ? "\nVollständigkeit der Veranstaltungsliste nicht bestätigt." : ""}${report.problems.length ? `\nTechnische Hinweise: ${report.problems.map(discoveryStatusLabel).join("; ")}` : ""}`);
    } catch (cause) { if (sequence === probeSequence.current) setProbeResult(cause instanceof Error ? cause.message : "Prüfung fehlgeschlagen"); }
    finally { if (sequence === probeSequence.current) setProbing(false); }
  }
  const [discoveryAliases, setDiscoveryAliases] = useState("");
  const [aliasesLoading, setAliasesLoading] = useState(false);
  const [aliasesReady, setAliasesReady] = useState(true);
  const [color, setColor] = useState<string>(PARTY_COLORS[0]);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState("");
  const [flyerFile, setFlyerFile] = useState<File | null>(null);
  const [removeFlyer, setRemoveFlyer] = useState(false);

  // Beziehungen einer Partyreihe
  const [residentDjIds, setResidentDjIds] = useState<string[]>([]);
  const [otherDjIds, setOtherDjIds] = useState<string[]>([]);
  const [organizerIds, setOrganizerIds] = useState<string[]>([]);
  const [standardGenres, setStandardGenres] = useState<string[]>([]);
  const [availableGenres, setAvailableGenres] = useState<ActiveGenre[]>([]);
  const [genresLoading, setGenresLoading] = useState(true);
  const [genresError, setGenresError] = useState("");

  const [djMentionDefault, setDjMentionDefault] =
    useState<DjMentionDefault>("manual");


  // Optionaler Turnus: Mo=1 ... Sa=6, So=0 (wie Date.getDay())
  const [recurrenceKind, setRecurrenceKind] =
    useState<RecurrenceChoice>("none");
  const [weekday, setWeekday] = useState(5);
  const [occurrence, setOccurrence] = useState(3);
  const [recurrenceNote, setRecurrenceNote] = useState("");

  useEffect(() => {
    let cancelled = false;
    async function loadGenres() {
      setGenresLoading(true);
      setGenresError("");
      try {
        const response = await fetch("/api/v1/genres", { credentials: "same-origin", cache: "no-store" });
        if (!response.ok) throw new Error(`Die Genreverwaltung antwortete mit HTTP ${response.status}.`);
        const body: unknown = await response.json();
        if (!Array.isArray(body)) throw new Error("Die Genreverwaltung lieferte kein gültiges Listenformat.");
        const genres = body.filter((item): item is ActiveGenre => {
          if (typeof item !== "object" || item === null) return false;
          const value = item as Record<string, unknown>;
          return typeof value.id === "string" && typeof value.displayName === "string" && value.active === true;
        }).sort((a, b) => a.displayName.localeCompare(b.displayName, "de-DE"));
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


  useEffect(() => {
    if (category !== "parties" || !editingId) return;
    let active = true;
    fetch(`/api/v1/discovery/parties/${encodeURIComponent(editingId)}/aliases`).then(async response => {
      if (!response.ok) throw new Error("Discovery-Aliase konnten nicht geladen werden.");
      const aliases: string[] = await response.json();
      if (active) { setDiscoveryAliases(aliases.join("\n")); setAliasesReady(true); }
    }).catch(e => { if (active) setError(e.message); }).finally(() => { if (active) setAliasesLoading(false); });
    return () => { active = false; };
  }, [category, editingId]);

  const items: CatalogItem[] =
    category === "parties" ? parties : venues;

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
    setAliasesReady(true); setAliasesLoading(false);
    setDiscoveryAliases("");
    setEditingId(null);
    setName("");
    setInstagram("");
    probeSequence.current++; setProbing(false); setWebsiteReport(null); setLocationSources([]); setSourceNotice("");
    setWebsiteUrl(""); setEventsUrl(""); setDiscoveryEnabled(false); setProbeResult("");
    setResidentDjIds([]);
    setOtherDjIds([]);
    setOrganizerIds([]);
    setStandardGenres([]);
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
    setWebsiteUrl(item.websiteUrl ?? ""); setEventsUrl(item.eventsUrl ?? ""); setDiscoveryEnabled(item.discoveryEnabled ?? false);

    if (category === "parties") {
      setAliasesLoading(true); setAliasesReady(false);
      const party = parties.find((p) => p.id === item.id);

      if (party) {
        setColor(party.color);
        setResidentDjIds([...party.residentDjIds]);
        setOtherDjIds([...party.otherDjIds]);
        setOrganizerIds([...party.organizerIds]);
        setStandardGenres([...party.standardGenres]);
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

  async function deleteItem(item: CatalogItem) {
    if (!window.confirm(`„${item.name}“ wirklich löschen? Verwendete Einträge werden geschützt.`)) return;
    setDeleteError("");
    setDeletingId(item.id);
    try {
      if (category === "parties") {
        await deleteParty(item.id);
      } else {
        await deleteVenue(item.id);
      }
    } catch (cause) {
      setDeleteError(cause instanceof Error ? cause.message : "Der Eintrag konnte nicht gelöscht werden.");
    } finally {
      setDeletingId(null);
    }
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
        standardGenres: [...standardGenres],
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
        const aliasesResponse = await fetch(`/api/v1/discovery/parties/${encodeURIComponent(savedParty.id)}/aliases`, {
          method: "PUT", headers: {"Content-Type":"application/json"}, body: JSON.stringify({aliases: discoveryAliases.split("\n").map(a => a.trim()).filter(Boolean)})
        });
        if (!aliasesResponse.ok) throw new Error("Discovery-Aliase konnten nicht gespeichert werden.");
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
        websiteUrl, eventsUrl, discoveryEnabled,
      };

      setSaving(true);
      try {
        let savedVenue: CatalogItem;
        if (editingId) {
          savedVenue = await updateVenue(updatedVenue);
        } else {
          savedVenue = await createVenue({
            name: updatedVenue.name,
            instagram: updatedVenue.instagram,
            websiteUrl, eventsUrl, discoveryEnabled,
          });
          setEditingId(savedVenue.id);
        }
        if (websiteReport && new URL(websiteReport.websiteUrl).href === new URL(websiteUrl).href) {
          const result = await fetch(`/api/v1/discovery/onboard/${encodeURIComponent(savedVenue.id)}/sources`, {
            method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ previewToken: websiteReport.previewToken }),
          });
          if (!result.ok) throw new Error("Location ist gespeichert, Quellenprüfung bitte erneut ausführen und speichern.");
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

  useWorkspaceAction(category === "parties" ? "Partyreihe anlegen" : "Location anlegen", createNew, saving);
  const columns: CollectionColumn<CatalogItem>[] = [
    {key: "name", label: category === "parties" ? "Partyreihe" : "Location", sortValue: item => item.name, render: item => <strong>{item.name}</strong>},
    {key: "instagram", label: "Instagram", sortValue: item => item.instagram, render: item => item.instagram ? `@${item.instagram}` : "Noch offen"},
    ...(category === "parties" ? [
      {key: "genres", label: "Genres", render: (item: CatalogItem) => <div className="cc-tags">{parties.find(party => party.id === item.id)?.standardGenres.map(genre => <span key={genre}>{genre}</span>)}</div>},
      {key: "recurrence", label: "Turnus", render: (item: CatalogItem) => describeRecurrence(parties.find(party => party.id === item.id)?.recurrence ?? null)},
      {key: "actors", label: "Beteiligte", render: (item: CatalogItem) => { const party = parties.find(party => party.id === item.id); return party ? <div className="cc-cell-details">{party.residentDjIds.length > 0 && <p>Residents: {getActorNames(party.residentDjIds)}</p>}{party.otherDjIds.length > 0 && <p>Weitere DJs: {getActorNames(party.otherDjIds)}</p>}{party.organizerIds.length > 0 && <p>Veranstalter: {getActorNames(party.organizerIds)}</p>}{party.residentDjIds.length + party.otherDjIds.length + party.organizerIds.length === 0 && "Noch offen"}</div> : "Noch offen"; }},
      {key: "flyer", label: "Dauerflyer", render: (item: CatalogItem) => parties.find(party => party.id === item.id)?.defaultFlyer ? "Vorhanden" : "Kein Flyer"},
    ] : [
      {key: "website", label: "Website", render: (item: CatalogItem) => <span className="cc-cell-details">{item.websiteUrl || "Noch offen"}</span>},
      {key: "discovery", label: "Discovery", sortValue: (item: CatalogItem) => item.discoveryEnabled ? 1 : 0, render: (item: CatalogItem) => <span className="cc-status">{item.discoveryEnabled ? "Aktiv" : "Inaktiv"}</span>},
      {key: "source", label: "Quellenstatus", render: (item: CatalogItem) => item.discoveryStatus ? discoveryStatusLabel(item.discoveryStatus) : "Noch nicht geprüft"},
    ]),
  ];
  function searchableItem(item: CatalogItem) {
    const party = category === "parties" ? parties.find(party => party.id === item.id) : undefined;
    return [item.name, item.instagram, item.websiteUrl, party?.standardGenres.join(" "), party ? getActorNames([...party.residentDjIds, ...party.otherDjIds, ...party.organizerIds]) : ""].join(" ");
  }

  return (
    <main className="app-page min-h-dvh bg-black px-4 py-6 text-white sm:py-8 md:px-10">
      <div className="mx-auto max-w-6xl">
        <header className="mt-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
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
          </div>
          <button
            type="button"
            onClick={createNew}
            className="cc-button cc-button-primary cc-page-primary"
          >
            + Neu anlegen
          </button>
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

        {deleteError && (
          <p role="alert" className="mt-5 rounded-lg border border-red-400/40 bg-red-950/30 p-3 text-sm text-red-300">
            {deleteError}
          </p>
        )}

        <section className="cc-catalog-list" aria-label={category === "parties" ? "Partyreihen" : "Locations"}>
          {(category === "parties" ? partiesLoading : venuesLoading) ? <p className="cc-empty" role="status">Einträge werden geladen …</p>
          : (category === "parties" ? partiesError : venuesError) ? <div className="cc-message" role="alert"><p>{category === "parties" ? partiesError : venuesError}</p><button type="button" className="cc-button" onClick={() => void (category === "parties" ? reloadParties() : reloadVenues())}>Erneut versuchen</button></div>
          : <DataCollection key={category} items={items} columns={columns} label={category === "parties" ? "Partyreihen" : "Locations"} storageKey={`noizy-view-${category}`} getName={item => item.name} onOpen={editItem} openDisabled={saving || deletingId !== null} query={search} onQueryChange={setSearch} searchText={searchableItem} selectedId={editorOpen ? editingId : null} actions={item => <div className="cc-inline-actions"><button type="button" className="cc-button" disabled={saving || deletingId !== null} onClick={() => editItem(item)}>Bearbeiten</button><button type="button" className="cc-button cc-button-danger" disabled={deletingId !== null} onClick={() => void deleteItem(item)}>{deletingId === item.id ? "Löschen ..." : "Löschen"}</button></div>} />}
        </section>

        {/* Editor */}
        <DashboardModal variant="drawer" protectChanges
          open={editorOpen}
          onClose={() => {
            setEditorOpen(false);
            resetForm();
          }}
          title={editingId ? "Eintrag bearbeiten" : "Neuer Eintrag"}
          description={category === "parties" ? "Partyreihe und optionale Standardwerte verwalten." : "Location-Stammdaten verwalten."}
        >
          <form
            onSubmit={saveItem}
            className="space-y-1"
          >
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

            {category === "locations" && <details className="mt-5 rounded-xl border border-white/10 p-4">
              <summary className="cursor-pointer font-semibold">Website &amp; Partysuche</summary>
              <label className="mt-4 block">Offizielle Website<input type="url" maxLength={2048} className={inputStyle} value={websiteUrl} onChange={e => changeWebsite(e.target.value)} placeholder="https://…" /></label>
              <label className="mt-4 block">Eventübersicht (optional)<input type="url" maxLength={2048} className={inputStyle} value={eventsUrl} onChange={e => setEventsUrl(e.target.value)} placeholder="https://…/programm" /></label>
              <button type="button" className="mt-4 rounded-lg border px-4 py-2" disabled={probing || !websiteUrl.trim()} onClick={() => void probeSource()}>{probing ? "Website wird geprüft …" : "Website automatisch prüfen"}</button>
              <p className="mt-2 whitespace-pre-wrap text-sm" role="status">{probeResult}</p><p className="mt-2 text-sm text-neutral-400">Gefundene Quellen werden mit der Location gespeichert. Die Probe legt keine Kalendertermine an; die automatische Suche bleibt separat einstellbar.</p>
              {editingId && <section className="mt-4 space-y-3" aria-label="Quellenstatus dieser Location"><button type="button" className="rounded-lg border px-4 py-2" onClick={()=>void (async()=>{try {const response=await fetch(`/api/v1/discovery/sources?venueId=${encodeURIComponent(editingId)}`);if(!response.ok)throw new Error("Quellenstatus konnte nicht geladen werden");const rows=await response.json();setLocationSources(Array.isArray(rows)?rows:[]);setSourceNotice(Array.isArray(rows)&&rows.length?"":"Noch keine Quellen geprüft.");}catch(cause){setSourceNotice(cause instanceof Error?cause.message:"Quellenstatus nicht verfügbar");}})()}>Quellenstatus aktualisieren</button><p>{sourceNotice}</p><SourceOverview sources={locationSources.filter(s=>s.venue_id===editingId)} /></section>}
              <label className="mt-4 flex gap-2"><input type="checkbox" checked={discoveryEnabled} onChange={e => setDiscoveryEnabled(e.target.checked)} />Automatische Suche aktivieren</label>
              <p className="mt-2 text-sm text-neutral-400">Offizielle Quellen dürfen eindeutige bekannte Partys in den Kalender übernehmen. Externe Quellen und unklare Termine werden geprüft.</p>
            </details>}

            {category === "parties" && (
              <>
                <details className="mt-5 rounded-xl border border-white/10 p-4"><summary>Alternative Namen für die Partysuche</summary>
                  <label className="mt-3 block">Explizite Aliase (ein Name pro Zeile)<textarea className={inputStyle} value={discoveryAliases} disabled={aliasesLoading} onChange={e => setDiscoveryAliases(e.target.value)} rows={3} /></label>
                </details>
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
                <fieldset className="mt-6 rounded-xl border border-white/10 p-5">
                  <legend className="px-2 text-lg font-semibold">Standardgenres</legend>
                  <p className="mt-1 text-sm text-neutral-400">
                    Diese Genres werden bei neuen Redaktionsentwürfen vorausgewählt. Special-Event-Zusätze bleiben eventbezogen.
                  </p>
                  {genresLoading && <p className="mt-4 text-sm text-neutral-500" role="status">Genres werden geladen ...</p>}
                  {genresError && <p className="mt-4 text-sm text-red-300" role="alert">{genresError}</p>}
                  {!genresLoading && !genresError && availableGenres.length === 0 && <p className="mt-4 text-sm text-neutral-500">Keine aktiven Genres in den Einstellungen vorhanden.</p>}
                  <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-3">
                    {availableGenres.map((genre) => (
                      <label key={genre.id} className="flex min-h-11 items-center gap-2 rounded-lg bg-neutral-900 px-3 py-2 text-sm">
                        <input type="checkbox" className="size-4 accent-white" checked={standardGenres.includes(genre.displayName)} onChange={() => setStandardGenres((current) => current.includes(genre.displayName) ? current.filter((item) => item !== genre.displayName) : [...current, genre.displayName])} disabled={saving || aliasesLoading || (category === "parties" && !aliasesReady)} />
                        <span>{genre.displayName}</span>
                      </label>
                    ))}
                    {!genresLoading && !genresError && standardGenres.filter(name => !availableGenres.some(genre => genre.displayName === name)).map(name => (
                      <label key={`unavailable-${name}`} className="flex min-h-11 items-center gap-2 rounded-lg bg-neutral-900 px-3 py-2 text-sm">
                        <input type="checkbox" className="size-4 accent-white" checked onChange={() => setStandardGenres(current => current.filter(item => item !== name))} disabled={saving || aliasesLoading || !aliasesReady} />
                        <span>{name} <span className="text-neutral-400">(nicht mehr aktiv oder verfügbar; zum Entfernen abwählen)</span></span>
                      </label>
                    ))}
                  </div>
                </fieldset>

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
                disabled={saving || aliasesLoading || (category === "parties" && !aliasesReady)}
                className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black"
              >
                {saving ? "Speichern ..." : "Übernehmen"}
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

      </div>
    </main>
  );
}
