"use client";
import { createContext, useContext, useEffect, useId, type Dispatch, type SetStateAction } from "react";

export type WorkspaceAction = {id: string; label: string; run: () => void; disabled?: boolean};
export const WorkspaceActionContext = createContext<Dispatch<SetStateAction<WorkspaceAction | null>> | null>(null);
export function useWorkspaceAction(label: string, run: () => void, disabled = false) {
  const setAction = useContext(WorkspaceActionContext);
  const id = useId();
  useEffect(() => {
    if (!setAction) return;
    setAction({id, label, run, disabled});
    return () => setAction(current => current?.id === id ? null : current);
  }, [setAction, id, label, run, disabled]);
}
