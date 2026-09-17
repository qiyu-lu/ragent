import { api } from "@/services/api";
import { storage } from "@/utils/storage";
import { activeResearch, type ResearchRun, type ResearchEvent } from "@/types/research";
import type { SourceRef } from "@/types";

export const createResearch = (request: {
  conversationId?: string;
  clientRequestId: string;
  goal: string;
  outputType: "REPORT" | "PLAN";
  allowedKbIds: string[];
}) => api.post<ResearchRun, ResearchRun>("/rag/research/runs", request);
export const getResearch = (id: string) =>
  api.get<ResearchRun, ResearchRun>(`/rag/research/runs/${encodeURIComponent(id)}`);
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
  const control = new AbortController();
  let cursor = after;
  let settled = false;
  const base = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
  const reconnectDelay = () =>
    new Promise<void>((resolve) => {
      const done = () => {
        clearTimeout(timer);
        control.signal.removeEventListener("abort", done);
        resolve();
      };
      const timer = setTimeout(done, 1500);
      control.signal.addEventListener("abort", done, { once: true });
      if (control.signal.aborted) done();
    });
  void (async () => {
    while (!control.signal.aborted && !settled) {
      try {
        const run = await getResearch(id);
        if (control.signal.aborted) break;
        callbacks.snapshot(run);
        if (!activeResearch(run)) break;
        const response = await fetch(
          `${base}/rag/research/runs/${encodeURIComponent(id)}/events?after=${cursor}`,
          {
            headers: {
              Accept: "text/event-stream",
              Authorization: storage.getToken() || "",
              "Last-Event-ID": String(cursor)
            },
            signal: control.signal
          }
        );

        if (
          !response.ok ||
          !response.body ||
          !response.headers.get("content-type")?.includes("text/event-stream")
        )
          throw new Error("RESEARCH_STREAM_UNAVAILABLE");
        callbacks.connection(false);
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        try {
          while (!control.signal.aborted) {
            const next = await reader.read();
            if (next.done) break;
            buffer += decoder.decode(next.value, { stream: true });
            buffer = buffer.replace(/\r\n/g, "\n");
            let end: number;
            while ((end = buffer.indexOf("\n\n")) >= 0) {
              const frame = buffer.slice(0, end);
              buffer = buffer.slice(end + 2);
              let name = "message";
              const data: string[] = [];
              for (const line of frame.split("\n")) {
                if (line.startsWith("event:")) name = line.slice(6).trim();
                if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
              }
              if (!data.length) continue;
              const parsed = JSON.parse(data.join("\n"));
              if (name === "snapshot") {
                callbacks.snapshot(parsed as ResearchRun);
                settled = !activeResearch(parsed as ResearchRun);
              } else if (name === "progress" || name === "artifact") {
                const event = parsed as ResearchEvent;
                if (event.sequence > cursor) {
                  callbacks.event(event);
                  cursor = event.sequence;
                }
              }
            }
          }
        } finally {
          await reader.cancel().catch(() => undefined);
          reader.releaseLock();
        }
      } catch {
        if (control.signal.aborted) break;
      }
      if (!settled && !control.signal.aborted) {
        callbacks.connection(true);
        await reconnectDelay();
      }
    }
  })();
  return () => control.abort();
}
