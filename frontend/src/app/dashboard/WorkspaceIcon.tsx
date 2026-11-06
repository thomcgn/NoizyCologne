import type { CSSProperties } from "react";

const paths = {
  overview: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  calendar: "M4 5h16v16H4z M4 10h16 M8 3v4 M16 3v4",
  discovery: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M16 8l-3 5-5 3 3-5z",
  editorial: "M4 4h16v16H4z M8 8h8 M8 12h8 M8 16h5",
  parties: "M9 3v12 M9 5l11-2v12 M9 8l11-2 M9 15a3 3 0 1 0 0 .1 M20 15a3 3 0 1 0 0 .1",
  actors: "M9 4a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M2 21v-3a6 6 0 0 1 12 0v3 M16 5a4 4 0 0 1 0 7 M18 15a5 5 0 0 1 4 5",
  locations: "M12 22s8-8 8-14a8 8 0 0 0-16 0c0 6 8 14 8 14z M12 5a3 3 0 1 0 0 6 3 3 0 0 0 0-6",
  genres: "M3 5h18 M3 12h18 M3 19h18 M8 2v6 M16 9v6 M10 16v6",
  automation: "M13 2L4 14h7l-1 8 10-12h-7z",
  settings: "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2",
  search: "M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14 M15 15l6 6",
  panel: "M3 4h18v16H3z M9 4v16 M14 9l3 3-3 3",
  alert: "M12 3L2 21h20z M12 9v5 M12 17v1",
  plus: "M12 4v16 M4 12h16",
  arrow: "M4 12h16 M14 6l6 6-6 6",
} as const;
export type WorkspaceIconName = keyof typeof paths;
export default function WorkspaceIcon({ name, style }: { name: WorkspaceIconName; style?: CSSProperties }) {
  return <svg className="cc-icon" style={style} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>;
}
