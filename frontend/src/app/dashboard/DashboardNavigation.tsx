"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";

export const navigationGroups = [
  { label: "Arbeitsbereich", items: [
    { href: "/dashboard", label: "Übersicht" },
    { href: "/dashboard/kalender", label: "Kalender" },
    { href: "/dashboard/vorschlaege", label: "Discovery" },
    { href: "/dashboard/redaktion", label: "Redaktion" },
  ] },
  { label: "Portfolio", items: [
    { href: "/dashboard/partys", label: "Partyreihen" },
    { href: "/dashboard/djs", label: "DJs / Akteure" },
    { href: "/dashboard/locations", label: "Locations" },
  ] },
  { label: "Verwaltung", items: [
    { href: "/dashboard/settings/genres", label: "Genres & Einstellungen" },
  ] },
];

export default function DashboardNavigation() {
  const pathname = usePathname();
  const [mobileOpen, setMobileOpen] = useState(false);

  return (
    <aside className="dashboard-sidebar">
      <div className="dashboard-mobile-header">
        <Link href="/dashboard" className="dashboard-logo-link" aria-label="NoizyCologne Dashboard">
          <Image src="/noizy-logo-white.png" alt="" width={180} height={54} className="logo logo-dark" priority />
          <Image src="/noizy-logo-black.png" alt="" width={180} height={54} className="logo logo-light" priority />
        </Link>
        <button
          type="button"
          className="mobile-nav-toggle"
          aria-expanded={mobileOpen}
          aria-controls="dashboard-navigation"
          onKeyDown={event => { if (event.key === "Escape") setMobileOpen(false); }}
          onClick={() => setMobileOpen((open) => !open)}
        >
          {mobileOpen ? "Schließen" : "Menü"}
        </button>
      </div>

      <Link href="/dashboard" className="dashboard-logo-link dashboard-desktop-logo" aria-label="NoizyCologne Dashboard">
        <Image src="/noizy-logo-white.png" alt="" width={180} height={54} className="logo logo-dark" priority />
        <Image src="/noizy-logo-black.png" alt="" width={180} height={54} className="logo logo-light" priority />
      </Link>

      <nav
        id="dashboard-navigation"
        onKeyDown={event => { if (event.key === "Escape") { setMobileOpen(false); document.querySelector<HTMLButtonElement>(".mobile-nav-toggle")?.focus(); } }}
        aria-label="Hauptnavigation"
        className={`dashboard-nav ${mobileOpen ? "is-open" : ""}`}
      >
        {navigationGroups.map(group => <section className="dashboard-nav-group" key={group.label} aria-label={group.label}><h2>{group.label}</h2>{group.items.map((item) => {
          const isActive =
            item.href === "/dashboard"
              ? pathname === item.href
              : pathname === item.href || pathname.startsWith(`${item.href}/`);

          return (
            <Link
              key={item.href}
              href={item.href}
              aria-current={isActive ? "page" : undefined}
              onClick={() => setMobileOpen(false)}
              className={`dashboard-nav-link ${
                isActive
                  ? "is-active"
                  : ""
              }`}
            >
              {item.label}
            </Link>
          );
        })}</section>)}
      </nav>
    </aside>
  );
}
