import { api } from "./api";

export type TaskStatus =
  | "READY"
  | "RUNNING"
  | "WAITING_INPUT"
  | "WAITING_APPROVAL"
  | "COMPLETED"
  | "CANCELLED"
  | "FAILED";
export interface TaskDocument {
  id: string;
  name: string;
  version: string;
}
export interface TaskEvidence {
  id: string;
  documentId: string;
  documentVersion: string;
  sheetName: string | null;
  cellRange: string | null;
  text: string;
  contentHash: string;
}
export interface TaskProposal {
  title: string;
  stationId: string;
  requirements: { text: string; evidenceIds: string[] }[];
}
export interface TaskSample {
  id: string;
  name: string;
  testType: string;
  labelVerified: boolean;
  handoffReady: boolean;
  status: string;
}
export interface TaskStation {
  id: string;
  name: string;
  testType: string;
  status: string;
  reservationRunId: string | null;
}
export interface TaskSummary {
  id: string;
  goal: string;
  sampleId: string;
  status: TaskStatus;
  updatedAt: number;
}
export interface TaskRun {
  id: string;
  status: TaskStatus;
  revision: number;
  leaseUntil: number;
  state: {
    goal: string;
    sampleId: string;
    document: TaskDocument;
    evidence: TaskEvidence[];
    proposal: TaskProposal | null;
    message: string;
    turns: number;
  };
  events: { sequence: number; type: string; message: string; detail: unknown; createdAt: number }[];
  submission: {
    runId: string;
    sampleId: string;
    stationId: string;
    documentVersion: string;
    createdAt: number;
  } | null;
}

const base = "/iron-ore/task-agent";
export const taskAgentApi = {
  documents: () => api.get<TaskDocument[], TaskDocument[]>(`${base}/documents`),
  samples: () => api.get<TaskSample[], TaskSample[]>(`${base}/samples`),
  stations: () => api.get<TaskStation[], TaskStation[]>(`${base}/stations`),
  runs: () => api.get<TaskSummary[], TaskSummary[]>(`${base}/runs`),
  initialize: () => api.post(`${base}/demo-data`),
  updateSample: (id: string, labelVerified: boolean, handoffReady: boolean) =>
    api.put(`${base}/samples/${encodeURIComponent(id)}`, { labelVerified, handoffReady }),
  start: (goal: string, documentId: string, sampleId: string) =>
    api.post<TaskRun, TaskRun>(`${base}/runs`, { goal, documentId, sampleId }),
  get: (id: string) => api.get<TaskRun, TaskRun>(`${base}/runs/${encodeURIComponent(id)}`),
  advance: (id: string) =>
    api.post<TaskRun, TaskRun>(`${base}/runs/${encodeURIComponent(id)}/advance`, undefined, {
      timeout: 180000
    }),
  reply: (id: string, message: string) =>
    api.post<TaskRun, TaskRun>(`${base}/runs/${encodeURIComponent(id)}/reply`, { message }),
  approve: (id: string, revision: number) =>
    api.post<TaskRun, TaskRun>(`${base}/runs/${encodeURIComponent(id)}/approve`, { revision }),
  cancel: (id: string) =>
    api.post<TaskRun, TaskRun>(`${base}/runs/${encodeURIComponent(id)}/cancel`)
};
