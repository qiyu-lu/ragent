export type ChatMode = "QA" | "REPORT" | "PLAN";
export type ResearchStatus =
  | "QUEUED"
  | "RUNNING"
  | "WAITING_INPUT"
  | "COMPLETED"
  | "PARTIAL"
  | "FAILED"
  | "CANCELLED"
  | "INTERRUPTED";
export interface GroundedRequirement {
  text: string;
  evidenceIds: string[];
}
export interface PlanDraft {
  prerequisites: GroundedRequirement[];
  steps: {
    order: number;
    action: string;
    evidenceIds: string[];
    parameters: {
      name: string;
      value: string | null;
      unit: string | null;
      evidenceIds: string[];
    }[];
  }[];
  resources: GroundedRequirement[];
  cautions: GroundedRequirement[];
  pendingItems: string[];
}
export interface ResearchCitation {
  index: number;
  evidenceId: string;
  docId: string;
  docName: string;
  documentVersion?: string;
  chunkIds: string[];
  excerpt: string;
  sourceLocation: Record<string, unknown>;
  truncated: boolean;
  sourceExtent: "CHUNK" | "AVAILABLE_EXCERPT";
}
export interface ResearchArtifact {
  outputType: "REPORT" | "PLAN";
  title: string;
  goal: string;
  userConstraints: { text: string; source: "user_input" }[];
  sections: { heading: string; text: string; evidenceIds: string[] }[];
  plan: PlanDraft | null;
  gaps: string[];
  conflicts: string[];
  citations: ResearchCitation[];
  markdown: string;
}
export interface ResearchRun {
  id: string;
  conversationId: string;
  clientRequestId: string;
  status: ResearchStatus;
  revision: number;
  epoch: number;
  brief: {
    goal: string;
    outputType: "REPORT" | "PLAN";
    constraints: string[];
    allowedKbIds: string[];
    allowedDocIds: string[];
  };
  state: {
    question?: string;
    artifactError?: string;
    subtasks?: Record<
      string,
      {
        task: { goal: string };
        status: string;
        readEvidenceIds?: string[];
        result?: { gaps: string[] };
      }
    >;
  };
  artifact: ResearchArtifact | null;
  usage: { modelCalls?: number; toolCalls?: number };
  errorSummary?: string;
  createdAt?: string;
  startedAt?: string;
  completedAt?: string;
}
export interface ResearchEvent {
  sequence: number;
  taskId: string;
  type: string;
  summary: string;
  payload: Record<string, unknown>;
  createdAt: string;
}
export function activeResearch(run: ResearchRun) {
  return run.status === "QUEUED" || run.status === "RUNNING";
}
