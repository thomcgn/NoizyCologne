"use client";
import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";
import WorkspaceIcon from "./WorkspaceIcon";
import { navigationGroups, workspaceRoute } from "./workspaceRoutes";
export { navigationGroups } from "./workspaceRoutes";

export default function DashboardNavigation({ collapsed = false, onCollapse }: {collapsed?: boolean; onCollapse?: () => void}) {
  const pathname = usePathname();
  const active = workspaceRoute(pathname);
  const [mobileOpen, setMobileOpen] = useState(false);
  return <aside className={`dashboard-sidebar ${collapsed ? "cc-sidebar-collapsed" : ""}`}>
    <div className="cc-brand-row">
      <Link href="/dashboard" className="dashboard-logo-link" aria-label="NoizyCologne Übersicht">
        <Image src="/noizy-logo-white.png" alt="" width={150} height={54} className="logo logo-dark" priority />
        <Image src="/noizy-logo-black.png" alt="" width={150} height={54} className="logo logo-light" priority />
      </Link>
      <button type="button" className="mobile-nav-toggle" aria-expanded={mobileOpen} aria-controls="dashboard-navigation" onClick={() => setMobileOpen(value => !value)}>{mobileOpen ? "Schließen" : "Menü"}</button>
      <button type="button" className="cc-sidebar-toggle cc-button-icon" aria-label={collapsed ? "Navigation erweitern" : "Navigation einklappen"} aria-expanded={!collapsed} onClick={onCollapse}><WorkspaceIcon name="panel" /></button>
    </div>
    <nav id="dashboard-navigation" aria-label="Hauptnavigation" className={`dashboard-nav ${mobileOpen ? "is-open" : ""}`} onKeyDown={event => {if (event.key === "Escape") {setMobileOpen(false); document.querySelector<HTMLButtonElement>(".mobile-nav-toggle")?.focus();}}}>
      {navigationGroups.map(group => <section className="dashboard-nav-group" key={group.label} aria-label={group.label}><h2>{group.label}</h2>{group.items.map(item => <Link key={item.href} href={item.href} title={collapsed ? item.label : undefined} aria-label={item.label} aria-current={active.href === item.href ? "page" : undefined} className={`dashboard-nav-link ${active.href === item.href ? "is-active" : ""}`} onClick={() => setMobileOpen(false)}><WorkspaceIcon name={item.icon} /><span>{item.label}</span></Link>)}</section>)}
    </nav>
  </aside>;
}
