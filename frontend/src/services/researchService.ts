import { api } from "@/services/api";
import { storage } from "@/utils/storage";
import { type ResearchRun, type ResearchEvent } from "@/types/research";
import { subscribeResearchStream } from "./researchStream";
import type { SourceRef } from "@/types";

export const createResearch = (request: {
  conversationId?: string;
  clientRequestId: string;
  goal: string;
  outputType: "REPORT" | "PLAN";
  allowedKbIds: string[];
}) => api.post<ResearchRun, ResearchRun>("/rag/research/runs", request);
export const getResearch = (id: string, signal?: AbortSignal) =>
  api.get<ResearchRun, ResearchRun>(`/rag/research/runs/${encodeURIComponent(id)}`, { signal });
export const listResearch = (conversationId: string) =>
  api.get<ResearchRun[], ResearchRun[]>("/rag/research/runs", { params: { conversationId } });
export const cancelResearch = (id: string) =>
  api.post<ResearchRun, ResearchRun>(`/rag/research/runs/${encodeURIComponent(id)}/cancel`);
export const answerResearch = (id: string, revision: number, answer: string) =>
  api.post<ResearchRun, ResearchRun>(`/rag/research/runs/${encodeURIComponent(id)}/input`, {
    revision,
    answer
  });
export const regenerateResearch = (id: string, clientRequestId: string) =>
  api.post<ResearchRun, ResearchRun>(`/rag/research/runs/${encodeURIComponent(id)}/regenerate`, {
    clientRequestId
  });

export function researchSources(run: ResearchRun): SourceRef[] {
  return (run.artifact?.citations ?? []).map((c) => ({
    index: c.index,
    evidenceId: c.evidenceId,
    docId: c.docId,
    docName: c.docName,
    documentVersion: c.documentVersion,
    chunkId: c.chunkIds[0],
    excerpt: c.excerpt,
    sheetName:
      typeof c.sourceLocation.sheetName === "string" ? c.sourceLocation.sheetName : undefined,
    cellRange:
      typeof c.sourceLocation.cellRange === "string" ? c.sourceLocation.cellRange : undefined,
    sourceLocation: c.sourceLocation,
    sourceExtent: c.sourceExtent,
    truncated: c.truncated
  }));
}

export async function getResearchSources(id: string): Promise<SourceRef[]> {
  const evidence = await api.get<
    {
      evidenceId: string;
      docId: string;
      documentName: string;
      documentVersion?: string;
      text: string;
      chunkIds: string[];
      sourceLocation: Record<string, unknown>;
      sourceExtent: string;
      truncated: boolean;
    }[],
    {
      evidenceId: string;
      docId: string;
      documentName: string;
      documentVersion?: string;
      text: string;
      chunkIds: string[];
      sourceLocation: Record<string, unknown>;
      sourceExtent: string;
      truncated: boolean;
    }[]
  >(`/rag/research/runs/${encodeURIComponent(id)}/sources`);
  return evidence.map((e) => ({
    evidenceId: e.evidenceId,
    docId: e.docId,
    docName: e.documentName,
    documentVersion: e.documentVersion,
    excerpt: e.text,
    chunkId: e.chunkIds[0],
    sourceLocation: e.sourceLocation,
    sourceExtent: e.sourceExtent,
    truncated: e.truncated
  }));
}

/** 订阅只发 GET；每次重连先取快照，再从最后处理过的 sequence 继续。 */
export function subscribeResearch(
  id: string,
  after: number,
  callbacks: {
    event: (event: ResearchEvent) => void;
    snapshot: (run: ResearchRun) => void;
    connection: (reconnecting: boolean) => void;
  }
) {
  const base = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
  return subscribeResearchStream(after, callbacks, {
    snapshot: (signal) => getResearch(id, signal),
    open: (cursor, signal) => fetch(
      `${base}/rag/research/runs/${encodeURIComponent(id)}/events?after=${cursor}`,
      { headers: { Accept: "text/event-stream", Authorization: storage.getToken() || "",
        "Last-Event-ID": String(cursor) }, signal }
    )
  });
}
