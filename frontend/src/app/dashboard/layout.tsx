
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
      <div className="relative">
        <div className="fixed right-4 top-4 z-20 flex gap-2 sm:right-8 sm:top-8">
          <ThemeToggle />
          <LogoutButton />
        </div>
        {children}
      </div>
    </CatalogProvider>
  );
}
