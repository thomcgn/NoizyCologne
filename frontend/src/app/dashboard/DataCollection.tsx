"use client";
import { useMemo, useState, useSyncExternalStore, type ReactNode } from "react";
import { preferenceStore } from "../ThemeProvider";
import { useRecordLink } from "./useRecordLink";

export type CollectionColumn<T> = {key: string; label: string; render: (item: T) => ReactNode; sortValue?: (item: T) => string | number; className?: string};
const views = ["table", "cards"] as const;
export default function DataCollection<T extends {id: string}>({items, columns, label, getName, onOpen, actions, renderCard, storageKey, searchText, searchLabel = "Einträge durchsuchen", query, onQueryChange, toolbar, empty = "Keine Einträge gefunden.", pageSize = 25, selectedId, openDisabled = false, initialSortKey}: {
  items: T[]; columns: CollectionColumn<T>[]; label: string; getName: (item: T) => string;
  onOpen?: (item: T) => void; actions?: (item: T) => ReactNode; renderCard?: (item: T) => ReactNode;
  storageKey: string; searchText?: (item: T) => string; searchLabel?: string; query?: string; onQueryChange?: (query: string) => void;
  toolbar?: ReactNode; empty?: ReactNode; pageSize?: number; selectedId?: string | null; openDisabled?: boolean; initialSortKey?: string;
}) {
  const viewStore = useMemo(() => preferenceStore(storageKey, views, "table", `${storageKey}-change`), [storageKey]);
  const view = useSyncExternalStore(viewStore.subscribe, viewStore.getSnapshot, viewStore.getServerSnapshot);
  const [localQuery, setLocalQuery] = useState("");
  const [sort, setSort] = useState({key: initialSortKey ?? columns[0]?.key ?? "", ascending: true});
  const [page, setPage] = useState(0);
  useRecordLink(items, onOpen, openDisabled);
  const search = query ?? localQuery;
  const matching = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase("de-DE");
    const rows = searchText && needle ? items.filter(item => searchText(item).toLocaleLowerCase("de-DE").includes(needle)) : items;
    const column = columns.find(column => column.key === sort.key);
    if (!column?.sortValue) return rows;
    return [...rows].sort((a, b) => {
      const first = column.sortValue!(a); const second = column.sortValue!(b);
      const result = typeof first === "number" && typeof second === "number" ? first - second : String(first).localeCompare(String(second), "de-DE", {numeric: true});
      return sort.ascending ? result : -result;
    });
  }, [items, search, searchText, columns, sort]);
  const lastPage = Math.max(0, Math.ceil(matching.length / pageSize) - 1);
  const currentPage = Math.min(page, lastPage);
  const visible = matching.slice(currentPage * pageSize, (currentPage + 1) * pageSize);
  function changeSearch(value: string) {setLocalQuery(value); onQueryChange?.(value); setPage(0);}
  return <div className={`cc-collection cc-view-${view}`} role="group" aria-label={label}>
    <div className="cc-collection-toolbar">
      <input type="search" className="cc-input cc-list-search" aria-label={searchLabel} placeholder="Suchen …" value={search} onChange={event => changeSearch(event.target.value)} />
      {toolbar}
      {renderCard && <div className="cc-view-toggle" role="group" aria-label={`${label}: Ansicht`}>{views.map(value => <button type="button" key={value} className="cc-button" aria-pressed={view === value} onClick={() => viewStore.set(value)}>{value === "table" ? "Liste" : "Karten"}</button>)}</div>}
      <span className="cc-list-count" role="status">{matching.length} Einträge</span>
    </div>
    {matching.length === 0 ? <div className="cc-empty">{empty}</div> : view === "cards" && renderCard ? <div className="cc-collection-cards">{visible.map(item => <div key={item.id} data-record-id={item.id}>{renderCard(item)}</div>)}</div> : <div className="cc-table-scroll"><table className="cc-table"><caption className="sr-only">{label}</caption><thead><tr>{columns.map(column => <th key={column.key} scope="col" aria-sort={sort.key === column.key ? sort.ascending ? "ascending" : "descending" : undefined}>{column.sortValue ? <button type="button" onClick={() => {setSort({key: column.key, ascending: sort.key !== column.key || !sort.ascending}); setPage(0);}}>{column.label}<span aria-hidden="true">{sort.key === column.key ? sort.ascending ? " ↑" : " ↓" : " ↕"}</span></button> : column.label}</th>)}{actions && <th scope="col">Aktionen</th>}</tr></thead><tbody>{visible.map(item => <tr key={item.id} data-record-id={item.id} aria-selected={selectedId ? selectedId === item.id : undefined}>{columns.map((column, index) => <td key={column.key} data-label={column.label} className={column.className}>{index === 0 && onOpen ? <button type="button" className="cc-record-open" disabled={openDisabled} data-record-open aria-label={`${getName(item)} öffnen`} onClick={() => onOpen(item)}>{column.render(item)}</button> : column.render(item)}</td>)}{actions && <td data-label="Aktionen" className="cc-row-actions">{actions(item)}</td>}</tr>)}</tbody></table></div>}
    {matching.length > pageSize && <nav className="cc-pagination" aria-label={`${label}: Seiten`}><span>{currentPage * pageSize + 1}–{Math.min((currentPage + 1) * pageSize, matching.length)} von {matching.length}</span><button type="button" className="cc-button" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>Zurück</button><button type="button" className="cc-button" disabled={currentPage === lastPage} onClick={() => setPage(currentPage + 1)}>Weiter</button></nav>}
  </div>;
}
