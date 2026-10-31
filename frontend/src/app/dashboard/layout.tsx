
import { CatalogProvider } from "./CatalogProvider";
import LogoutButton from "./LogoutButton";
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
        <div className="fixed right-5 top-5 z-20 md:right-8 md:top-8">
          <LogoutButton />
        </div>
        {children}
      </div>
    </CatalogProvider>
  );
}
