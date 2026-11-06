import type { WorkspaceIconName } from "./WorkspaceIcon";
export type WorkspaceRoute = { href: string; label: string; icon: WorkspaceIconName; aliases?: string[] };
export const navigationGroups: {label: string; items: WorkspaceRoute[]}[] = [
  {label: "Arbeitsbereich", items: [
    {href: "/dashboard", label: "Übersicht", icon: "overview"},
    {href: "/dashboard/kalender", label: "Kalender", icon: "calendar"},
    {href: "/dashboard/vorschlaege", label: "Discovery", icon: "discovery"},
    {href: "/dashboard/redaktion", label: "Redaktion", icon: "editorial"},
  ]},
  {label: "Portfolio", items: [
    {href: "/dashboard/partys", label: "Partyreihen", icon: "parties", aliases: ["/dashboard/portfolio"]},
    {href: "/dashboard/djs", label: "DJs / Akteure", icon: "actors", aliases: ["/dashboard/portfolio/akteure"]},
    {href: "/dashboard/locations", label: "Locations", icon: "locations"},
  ]},
  {label: "Verwaltung", items: [
    {href: "/dashboard/settings/genres", label: "Genres", icon: "genres"},
    {href: "/dashboard/automatisierung", label: "Automatisierung", icon: "automation"},
    {href: "/dashboard/settings", label: "Einstellungen", icon: "settings"},
  ]},
];
export function workspaceRoute(pathname: string) {
  return navigationGroups.flatMap(group => group.items).filter(item => [item.href, ...(item.aliases ?? [])].some(href => pathname === href || (href !== "/dashboard" && pathname.startsWith(`${href}/`)))).sort((a, b) => Math.max(b.href.length, ...(b.aliases?.map(value => value.length) ?? [])) - Math.max(a.href.length, ...(a.aliases?.map(value => value.length) ?? [])))[0] ?? navigationGroups[0].items[0];
}
