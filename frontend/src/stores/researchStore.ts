import { create } from "zustand";
import { toast } from "sonner";
import type { SourceRef } from "@/types";
import {
  activeResearch,
  type ChatMode,
  type ResearchRun,
  type ResearchEvent
} from "@/types/research";
import {
  createResearch,
  listResearch,
  subscribeResearch,
  answerResearch,
  cancelResearch,
  regenerateResearch,
  getResearchSources,
  getResearch
} from "@/services/researchService";
import { getKnowledgeBases, type KnowledgeBase } from "@/services/knowledgeService";
import { useChatStore } from "@/stores/chatStore";

const subscriptions = new Map<string, () => void>();
const pendingCreates = new Map<string, string>();
const pendingRegenerations = new Map<string, string>();
interface ResearchState {
  mode: ChatMode;
  runs: Record<string, ResearchRun>;
  events: Record<string, ResearchEvent[]>;
  readSources: Record<string, SourceRef[]>;
  cursors: Record<string, number>;
  reconnecting: Record<string, boolean>;
  knowledgeBases: KnowledgeBase[];
  selectedKbIds: string[];
  scopeLoaded: boolean;
  isSubmitting: boolean;
  setMode: (mode: ChatMode) => void;
  loadScope: () => Promise<void>;
  toggleKb: (id: string) => void;
  submit: (goal: string) => Promise<void>;
  watchConversation: (id: string) => () => void;
  answer: (run: ResearchRun, answer: string) => Promise<void>;
  cancel: (id: string) => Promise<void>;
  regenerate: (id: string) => Promise<void>;
}

export const useResearchStore = create<ResearchState>((set, get) => {
  const upsert = (run: ResearchRun) =>
    set((s) => {
      const previous = s.runs[run.id];
      if (previous && (previous.epoch > run.epoch || previous.revision > run.revision)) return s;
      return { runs: { ...s.runs, [run.id]: run } };
    });
  const attach = (id: string) => {
    subscriptions.get(id)?.();
    subscriptions.set(
      id,
      subscribeResearch(id, get().cursors[id] ?? 0, {
        snapshot: upsert,
        connection: (reconnecting) =>
          set((s) => ({ reconnecting: { ...s.reconnecting, [id]: reconnecting } })),
        event: (event) =>
          set((s) => {
            const sources = [...(s.readSources[id] ?? [])];
            if (event.type === "SOURCE_READ") {
              const p = event.payload;
              const location = (p.sourceLocation ?? {}) as Record<string, unknown>;
              if (!sources.some((source) => source.evidenceId === p.evidenceId))
                sources.push({
                  docId: String(p.docId),
                  docName: String(p.docName),
                  evidenceId: String(p.evidenceId),
                  excerpt: String(p.excerpt ?? ""),
                  documentVersion:
                    typeof p.documentVersion === "string" ? p.documentVersion : undefined,
                  sourceLocation: location,
                  sourceExtent: String(p.sourceExtent),
                  truncated: Boolean(p.truncated),
                  sheetName:
                    typeof location.sheetName === "string" ? location.sheetName : undefined,
                  cellRange: typeof location.cellRange === "string" ? location.cellRange : undefined
                });
            }
            return {
              events: { ...s.events, [id]: [...(s.events[id] ?? []), event].slice(-80) },
              cursors: { ...s.cursors, [id]: event.sequence },
              readSources: { ...s.readSources, [id]: sources }
            };
          })
      })
    );
  };
  const showConversation = async (run: ResearchRun) => {
    upsert(run);
    const chat = useChatStore.getState();
    if (!chat.currentSessionId)
      useChatStore.setState({ currentSessionId: run.conversationId, isCreatingNew: false });
    attach(run.id);
    await chat.fetchSessions();
  };
  return {
    mode: "QA",
    runs: {},
    events: {},
    readSources: {},
    cursors: {},
    reconnecting: {},
    knowledgeBases: [],
    selectedKbIds: [],
    scopeLoaded: false,
    isSubmitting: false,
    setMode: (mode) => {
      set({ mode });
      if (mode !== "QA")
        void get()
          .loadScope()
          .catch(() => undefined);
    },
    loadScope: async () => {
      if (get().scopeLoaded) return;
      const page = await getKnowledgeBases(1, 100);
      const knowledgeBases = page.filter((k) => k.collectionName);
      set({ knowledgeBases, selectedKbIds: knowledgeBases.map((k) => k.id), scopeLoaded: true });
    },
    toggleKb: (id) =>
      set((s) => ({
        selectedKbIds: s.selectedKbIds.includes(id)
          ? s.selectedKbIds.filter((k) => k !== id)
          : [...s.selectedKbIds, id]
      })),
    submit: async (goal) => {
      if (get().isSubmitting) return;
      set({ isSubmitting: true });
      try {
        await get().loadScope();
        const mode = get().mode;
        if (mode === "QA") return;
        if (!get().selectedKbIds.length) throw new Error("请先选择研究知识库");
        const conversationId = useChatStore.getState().currentSessionId ?? undefined;
        const key = JSON.stringify([conversationId, goal, mode, get().selectedKbIds]);
        const clientRequestId = pendingCreates.get(key) ?? crypto.randomUUID();
        pendingCreates.set(key, clientRequestId);
        const run = await createResearch({
          conversationId,
          clientRequestId,
          goal,
          outputType: mode,
          allowedKbIds: get().selectedKbIds
        });
        pendingCreates.delete(key);
        await showConversation(run);
      } catch (failure) {
        toast.error((failure as Error).message || "创建研究失败");
        throw failure;
      } finally {
        set({ isSubmitting: false });
      }
    },
    watchConversation: (id) => {
      let mounted = true;
      void listResearch(id)
        .then((runs) => {
          if (!mounted) return;
          runs.forEach((run) => {
            upsert(run);
            if (activeResearch(run)) attach(run.id);
            if (!run.artifact)
              void getResearchSources(run.id)
                .then((sources) => {
                  if (mounted)
                    set((state) => ({
                      readSources: {
                        ...state.readSources,
                        [run.id]: Array.from(
                          new Map(
                            [...sources, ...(state.readSources[run.id] ?? [])].map((source) => [
                              source.evidenceId,
                              source
                            ])
                          ).values()
                        )
                      }
                    }));
                })
                .catch(() => undefined);
          });
        })
        .catch(() => {
          if (mounted) toast.error("研究记录加载失败，可刷新重试");
        });
      return () => {
        mounted = false;
        Object.values(get().runs)
          .filter((run) => run.conversationId === id)
          .forEach((run) => {
            subscriptions.get(run.id)?.();
            subscriptions.delete(run.id);
          });
      };
    },
    answer: async (run, answer) => {
      try {
        upsert(await answerResearch(run.id, run.revision, answer));
        attach(run.id);
      } catch (error) {
        const current = await getResearch(run.id);
        upsert(current);
        if (activeResearch(current)) attach(run.id);
        throw error;
      }
    },
    cancel: async (id) => {
      upsert(await cancelResearch(id));
      subscriptions.get(id)?.();
      subscriptions.delete(id);
    },
    regenerate: async (id) => {
      if (get().isSubmitting) return;
      set({ isSubmitting: true });
      const clientRequestId = pendingRegenerations.get(id) ?? crypto.randomUUID();
      pendingRegenerations.set(id, clientRequestId);
      try {
        const run = await regenerateResearch(id, clientRequestId);
        pendingRegenerations.delete(id);
        await showConversation(run);
      } finally {
        set({ isSubmitting: false });
      }
    }
  };
});
