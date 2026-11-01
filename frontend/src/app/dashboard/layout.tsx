
import { CatalogProvider } from "./CatalogProvider";
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
      <div className="dashboard-shell relative">
        <div className="dashboard-controls flex justify-end gap-2 lg:fixed lg:right-8 lg:top-8 lg:z-20">
          <ThemeToggle />
          <LogoutButton />
        </div>
        {children}
      </div>
    </CatalogProvider>
  );
}

