import type { DiscoverySource } from "../SourceOverview";

export type DiscoveryAssessment = {
  recognition: { status: "SINGLE" | "COOPERATION" | "AMBIGUOUS" | "NONE"; parties: { id: string; name: string; matchedName: string }[] };
  verification: { status: "VERIFIED" | "MISSING_DATA" | "CONFLICTING_DATA" | "SPECULATIVE"; reasons: string[]; date: string | null };
  lifecycle: "ACTIVE" | "CANCELLED" | "CANCELLATION_UNCONFIRMED" | "POSTPONED" | "RESCHEDULED" | "LINEUP_CHANGE";
  automaticImportEligible: boolean;
  importBlockers: string[];
};
export type Candidate = {id: string; venue_id?: string | null; special_source?: boolean; source_name?: string; reported_location?: string; title: string; venue_name: string; instagram: string; source_url: string; event_date: string | null; party_id: string | null; status: string; reason: string; evidence: string; inquiry_status: string; dm_text: string; calendar_entry_id: string | null; decision_origin: string; matching_class: string; automatic_reason: string; matcher_version: string; evaluated_at: string | null; previous_status: string | null; last_evaluation_id: string | null; assessment?: DiscoveryAssessment; genreCoverage?: { matched:number; total:number; percent:number; names:string[]; eligible:boolean; sourceNames?:string[]; standardNames?:string[]; basis?:"SOURCE"|"STANDARD"|"MISSING" }};
export type Source = DiscoverySource;
export type Operation = { id: string; kind: string; status: string; report: string; summary: string };
export type Summary = { evaluated: number; newPossibleMatches: number; stillIgnored: number; preservedManual: number; reclassified: number; failed: number };
export type Diagnosis = { id: number; title: string; venue_name: string; old_status: string | null; new_status: string; reason: string; origin: string };
export type Overview = {metrics: { automaticallyIgnored: number; manuallyRejected: number; needsReview: number; reevaluated: number; unverified: number; possible: number; open: number; archive: number }; operations: Operation[]; diagnoses: Diagnosis[]; automaticallyImported: number; candidates: Candidate[]; sources: Source[]; counts: {status: string; count: number}[]; runs: {id: string; started_at: string; status: string; report: string}[]};
