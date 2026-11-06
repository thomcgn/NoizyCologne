"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState, useSyncExternalStore, type ReactNode } from "react";
import { preferenceStore } from "../ThemeProvider";
import ThemeToggle from "../ThemeToggle";
import DashboardNavigation from "./DashboardNavigation";
import DisplayPreferences from "./DisplayPreferences";
import LogoutButton from "./LogoutButton";
import WorkspaceIcon from "./WorkspaceIcon";
import { workspaceRoute } from "./workspaceRoutes";
import { WorkspaceActionContext, type WorkspaceAction } from "./workspaceActions";
import { useCatalog } from "./CatalogProvider";

const sidebar = preferenceStore("noizy-sidebar", ["expanded", "collapsed"], "expanded", "noizy-sidebar-change");
export default function ApplicationShell({children}: {children: ReactNode}) {
  const pathname = usePathname();
  const route = workspaceRoute(pathname);
  const collapsed = useSyncExternalStore(sidebar.subscribe, sidebar.getSnapshot, sidebar.getServerSnapshot) === "collapsed";
  const [action, setAction] = useState<WorkspaceAction | null>(null);
  const {drafts, draftsLoading, draftsError} = useCatalog();
  const needsReview = drafts.filter(draft => draft.approvalNeedsReview || draft.publicationBlockers?.length).length;
  return <WorkspaceActionContext.Provider value={setAction}>
    <div className={`dashboard-shell cc-shell ${collapsed ? "cc-is-collapsed" : ""}`}>
      <a className="cc-skip-link" href="#workspace-content">Zum Arbeitsbereich</a>
      <div className="dashboard-frame">
        <DashboardNavigation collapsed={collapsed} onCollapse={() => sidebar.set(collapsed ? "expanded" : "collapsed")} />
        <div className="cc-workspace">
          <header className="cc-workspace-header">
            <div className="cc-workspace-title"><WorkspaceIcon name={route.icon} /><span>{route.label}</span></div>
            <div className="cc-header-actions">
              {!draftsLoading && !draftsError && needsReview > 0 && <Link href="/dashboard/redaktion" className="cc-attention" aria-label={`${needsReview} Redaktionsentwürfe mit Prüfhinweis`}><WorkspaceIcon name="alert" /><span>{needsReview}</span></Link>}
              {action && <button className="cc-button cc-button-primary cc-context-action" disabled={action.disabled} onClick={action.run}>{action.label}</button>}
              <DisplayPreferences /><ThemeToggle /><LogoutButton />
            </div>
          </header>
          <div id="workspace-content" tabIndex={-1} className="cc-workspace-content">{children}</div>
        </div>
      </div>
    </div>
  </WorkspaceActionContext.Provider>;
}
