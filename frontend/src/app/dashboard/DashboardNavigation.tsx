"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";

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

  return (
    <aside className="dashboard-sidebar border-b border-white/10 p-4 sm:p-6 lg:border-r lg:border-b-0">
      <div className="flex items-center gap-3">
        <div className="theme-brand rounded-xl bg-black px-3 py-2">
          <Image
            src="/noizy-logo-white.png"
            alt="NoizyCologne"
            width={112}
            height={112}
            className="h-10 w-10 object-contain"
            priority
          />
        </div>
        <div className="text-xl font-black tracking-tight sm:text-2xl">
          NOIZY<span className="text-neutral-500">COLOGNE</span>
        </div>
      </div>

      <p className="mt-1 text-xs tracking-[0.2em] text-neutral-500 uppercase">
        Team Dashboard
      </p>

      <nav
        aria-label="Hauptnavigation"
        className="mt-6 grid grid-cols-2 gap-2 text-sm sm:mt-10 sm:flex sm:space-y-2 lg:block"
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
              className={`block rounded-lg px-3 py-3 text-center sm:px-4 lg:text-left ${
                isActive
                  ? "bg-white font-semibold text-black"
                  : "text-neutral-300 hover:bg-white/10"
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
