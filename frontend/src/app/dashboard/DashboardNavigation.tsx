"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";

const navigationItems = [
  { href: "/dashboard", label: "Übersicht" },
  { href: "/dashboard/kalender", label: "Kalender" },
  { href: "/dashboard/partys", label: "Partys" },
  { href: "/dashboard/djs", label: "DJs" },
  { href: "/dashboard/locations", label: "Locations" },
  { href: "/dashboard/redaktion", label: "Redaktion" },
  { href: "/dashboard/settings/genres", label: "Einstellungen" },
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
        aria-label="Hauptnavigation"
        className={`dashboard-nav ${mobileOpen ? "is-open" : ""}`}
      >
        {navigationItems.map((item) => {
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
        })}
      </nav>
    </aside>
  );
}
