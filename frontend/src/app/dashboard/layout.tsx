
import { CatalogProvider } from "./CatalogProvider";
import DashboardNavigation from "./DashboardNavigation";
import LogoutButton from "./LogoutButton";
import ThemeToggle from "../ThemeToggle";
import { requireSession } from "@/lib/auth";

export default async function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  await requireSession();

  return (
    <CatalogProvider>
      <div className="dashboard-shell relative min-h-dvh bg-black text-white">
        <div className="dashboard-controls flex justify-end gap-2 lg:fixed lg:right-8 lg:top-8 lg:z-20">
          <ThemeToggle />
          <LogoutButton />
        </div>
        <div className="mx-auto min-h-dvh max-w-7xl lg:grid lg:grid-cols-[240px_1fr]">
          <DashboardNavigation />
          <div className="min-w-0">{children}</div>
        </div>
      </div>
    </CatalogProvider>
  );
}
