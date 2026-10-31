
import { CatalogProvider } from "./CatalogProvider";

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <CatalogProvider>
      {children}
    </CatalogProvider>
  );
}
