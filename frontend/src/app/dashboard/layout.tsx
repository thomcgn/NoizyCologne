
import { CatalogProvider } from "./CatalogProvider";
import DashboardNavigation from "./DashboardNavigation";
import LogoutButton from "./LogoutButton";
import ThemeToggle from "../ThemeToggle";
import DisplayPreferences from "./DisplayPreferences";
import { requireSession } from "@/lib/auth";

export default async function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  await requireSession();

  return (
    <CatalogProvider>
      <div className="dashboard-shell min-h-dvh bg-black text-white">
        <div className="dashboard-controls flex justify-end gap-2">
          <DisplayPreferences />
          <ThemeToggle />
          <LogoutButton />
        </div>
        <div className="dashboard-frame">
          <DashboardNavigation />
          <div className="min-w-0">{children}</div>
        </div>
      </div>
    </CatalogProvider>
  );
}
