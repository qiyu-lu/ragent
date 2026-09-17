import { useChatStore } from "@/stores/chatStore";
import { useResearchStore } from "@/stores/researchStore";
import { activeResearch } from "@/types/research";

/** 欢迎页与聊天输入共用分流；deepThinking 仍只属于普通问答。 */
export function useChatInputActions() {
  const chat = useChatStore();
  const research = useResearchStore();
  const activeRun = Object.values(research.runs)
    .filter((run) => run.conversationId === chat.currentSessionId && activeResearch(run))
    .slice(-1)[0];
  const researchWorking = research.mode !== "QA" && Boolean(activeRun);
  return {
    ...chat,
    mode: research.mode,
    isSubmitting: research.isSubmitting,
    isStreaming: chat.isStreaming || researchWorking,
    sendMessage: (content: string) =>
      research.mode === "QA" ? chat.sendMessage(content) : research.submit(content),
    cancelGeneration: () => {
      if (researchWorking && activeRun) void research.cancel(activeRun.id).catch(() => undefined);
      else chat.cancelGeneration();
    }
  };
}
