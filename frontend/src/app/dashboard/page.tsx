import Link from "next/link";
import Image from "next/image";

const modules = [
  {
    title: "Kalender",
    description: "Veranstaltungen, Wiederholungen und Location-Sperren verwalten.",
    status: "In Vorbereitung",
  },
  {
    title: "Partys",
    description: "Partyreihen, Farben, Turnus und Beteiligte verwalten.",
    status: "In Vorbereitung",
  },
  {
    title: "Redaktionsplanung",
    description: "Flyer, Captions, Mentions und Feed-Reihenfolge organisieren.",
    status: "Entwurfsansicht verfügbar",
  },
];

export default function DashboardPage() {
  return (
    <div className="app-page min-h-dvh bg-black text-white">
      <div className="mx-auto min-h-dvh max-w-7xl lg:grid lg:grid-cols-[240px_1fr]">

        {/* Navigation */}
        <aside className="dashboard-sidebar border-b border-white/10 p-4 sm:p-6 lg:border-r lg:border-b-0">
          <div className="flex items-center gap-3">
            <div className="theme-brand rounded-xl bg-black px-3 py-2">
              <Image src="/noizy-logo-white.png" alt="NoizyCologne" width={112} height={112} className="h-10 w-10 object-contain" priority />
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
            <Link
              href="/dashboard"
              className="rounded-lg bg-white px-3 py-3 text-center font-semibold text-black sm:px-4 lg:block lg:text-left"
            >
              Übersicht
            </Link>

            <Link
              href="/dashboard/kalender"
              className="block rounded-lg px-3 py-3 text-center text-neutral-300 hover:bg-white/10 sm:px-4 lg:text-left"
            >
              Kalender
            </Link>

            <Link
              href="/dashboard/partys"
              className="block rounded-lg px-3 py-3 text-center text-neutral-300 hover:bg-white/10 sm:px-4 lg:text-left"
            >
              Partys
            </Link>

            <Link
              href="/dashboard/djs"
              className="block rounded-lg px-3 py-3 text-center text-neutral-300 hover:bg-white/10 sm:px-4 lg:text-left"
            >
              DJs
            </Link>

            <Link
              href="/dashboard/locations"
              className="block rounded-lg px-3 py-3 text-center text-neutral-300 hover:bg-white/10 sm:px-4 lg:text-left"
            >
              Locations
            </Link>

            <Link
              href="/dashboard/redaktion"
              className="block rounded-lg px-3 py-3 text-center text-neutral-300 hover:bg-white/10 sm:px-4 lg:text-left"
            >
              Redaktion
            </Link>

            <Link
              href="/dashboard/settings/genres"
              className="block rounded-lg px-3 py-3 text-center text-neutral-300 hover:bg-white/10 sm:px-4 lg:text-left"
            >
              Einstellungen
            </Link>

          </nav>
        </aside>

        {/* Dashboard */}
        <main className="p-6 md:p-10">
          <header className="border-b border-white/10 pb-8">
            <p className="text-xs tracking-[0.2em] text-neutral-500 uppercase">
              NoizyCologne / Dashboard
            </p>

            <h1 className="mt-3 text-3xl font-bold tracking-tight md:text-4xl">
              Übersicht
            </h1>

            <p className="mt-3 text-neutral-400">
              Deine zentrale Verwaltung für Kölns Partyszene.
            </p>
          </header>

          {/* Module */}
          <section className="mt-10 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {modules.map((module) => (
              <article
                key={module.title}
                className="rounded-xl border border-white/10 bg-neutral-950 p-6"
              >
                <h2 className="text-xl font-semibold">
                  {module.title}
                </h2>

                <p className="mt-3 min-h-20 text-sm leading-6 text-neutral-400">
                  {module.description}
                </p>

                <div className="mt-5 border-t border-white/10 pt-4 text-xs text-neutral-500">
                  {module.title === "Redaktionsplanung" ? (
                    <Link href="/dashboard/redaktion" className="text-neutral-300 hover:text-white">
                      {module.status} →
                    </Link>
                  ) : module.status}
                </div>
              </article>
            ))}
          </section>

          {/* Kalenderbereich */}
          <section className="mt-10 rounded-xl border border-white/10 bg-neutral-950 p-6">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 className="text-xl font-semibold">
                Veranstaltungskalender
              </h2>

              <span className="text-sm text-neutral-500">
                Noch keine Daten
              </span>
            </div>

            <div className="mt-6 flex min-h-64 items-center justify-center rounded-lg border border-dashed border-white/10 p-6 text-center">
              <div>
                <p className="font-medium">
                  Dein Kalender entsteht hier.
                </p>

                <p className="mt-2 max-w-sm text-sm leading-6 text-neutral-500">
                  Künftig kannst du Veranstaltungen, regelmäßige
                  Partys und Location-Sperren direkt verwalten.
                </p>
              </div>
            </div>
          </section>
        </main>
      </div>
    </div>
  );
}
