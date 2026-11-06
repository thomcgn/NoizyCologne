
import { CatalogProvider } from "./CatalogProvider";
import ApplicationShell from "./ApplicationShell";
import { requireSession } from "@/lib/auth";

export default async function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  await requireSession();

  return (
    <CatalogProvider>
      <ApplicationShell>{children}</ApplicationShell>
    </CatalogProvider>
  );
}
