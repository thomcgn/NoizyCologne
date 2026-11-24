"use client";

// Der gemeinsame Hook liegt im DashboardModal, damit alle Aufrufer und die
// bestehenden jsdom-Fixtures denselben Vertrag ohne Sonderauflösung nutzen.
export { useDirtyGuard, useDomDirtyGuard, type DirtyGuard } from "./DashboardModal";
