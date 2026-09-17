import * as React from "react";
import { useNavigate, useParams } from "react-router-dom";

import { ChatInput } from "@/components/chat/ChatInput";
import { MessageList } from "@/components/chat/MessageList";
import { SourcesPanel } from "@/components/chat/SourcesPanel";
import { MainLayout } from "@/components/layout/MainLayout";
import { useChatStore } from "@/stores/chatStore";
import { useResearchStore } from "@/stores/researchStore";
import type { Message } from "@/types";
import { activeResearch } from "@/types/research";

export function ChatPage() {
  const navigate = useNavigate();
  const { sessionId } = useParams<{ sessionId: string }>();
  const {
    messages,
    isLoading,
    isStreaming,
    currentSessionId,
    sessions,
    isCreatingNew,
    fetchSessions,
    selectSession,
    createSession
  } = useChatStore();
  const runs = useResearchStore((state) => state.runs);
  const watchConversation = useResearchStore((state) => state.watchConversation);
  React.useEffect(() => {
    if (currentSessionId) return watchConversation(currentSessionId);
  }, [currentSessionId, watchConversation]);
  const shownMessages = React.useMemo(() => {
    const researchMessages: Message[] = Object.values(runs)
      .filter((run) => run.conversationId === currentSessionId)
      .flatMap((run) => [
        {
          id: `research-user-${run.id}`,
          role: "user",
          content: run.brief.goal,
          status: "done",
          createdAt: run.createdAt ?? run.startedAt
        },
        {
          id: run.id,
          role: "assistant",
          content: "",
          researchRunId: run.id,
          status: "done",
          createdAt: run.createdAt ?? run.startedAt
        }
      ]);
    return [...messages, ...researchMessages].sort(
      (a, b) =>
        (a.createdAt ? Date.parse(a.createdAt) : Infinity) -
        (b.createdAt ? Date.parse(b.createdAt) : Infinity)
    );
  }, [messages, runs, currentSessionId]);
  const showWelcome = shownMessages.length === 0 && !isLoading;
  const hasRunningResearch = Object.values(runs).some(
    (run) => run.conversationId === currentSessionId && activeResearch(run)
  );
  const [sessionsReady, setSessionsReady] = React.useState(false);
  const sessionExists = React.useMemo(() => {
    if (!sessionId) return false;
    return (
      sessions.some((session) => session.id === sessionId) ||
      Object.values(runs).some((run) => run.conversationId === sessionId)
    );
  }, [sessionId, sessions, runs]);

  React.useEffect(() => {
    let active = true;
    fetchSessions()
      .catch(() => null)
      .finally(() => {
        if (active) {
          setSessionsReady(true);
        }
      });
    return () => {
      active = false;
    };
  }, [fetchSessions]);

  React.useEffect(() => {
    if (sessionId) {
      if (sessionsReady && !sessionExists) {
        createSession().catch(() => null);
        navigate("/chat", { replace: true });
        return;
      }
      selectSession(sessionId).catch(() => null);
      return;
    }
    if (!sessionsReady) {
      return;
    }
    if (isCreatingNew) {
      return;
    }
    if (currentSessionId) {
      return;
    }
    createSession().catch(() => null);
  }, [
    sessionId,
    sessionsReady,
    sessionExists,
    isCreatingNew,
    currentSessionId,
    selectSession,
    createSession,
    navigate
  ]);

  React.useEffect(() => {
    if (currentSessionId && currentSessionId !== sessionId) {
      navigate(`/chat/${currentSessionId}`, { replace: true });
    }
  }, [currentSessionId, sessionId, navigate]);

  return (
    <MainLayout>
      <div className="flex h-full">
        <div className="flex h-full min-w-0 flex-1 flex-col bg-white">
          <div className="flex-1 min-h-0">
            <MessageList
              messages={shownMessages}
              isLoading={isLoading}
              isStreaming={isStreaming || hasRunningResearch}
              sessionKey={currentSessionId}
            />
          </div>
          {showWelcome ? null : (
            <div className="relative z-20 bg-white">
              <div className="mx-auto max-w-[840px] px-6 pt-1 pb-4">
                <ChatInput />
              </div>
            </div>
          )}
        </div>
        <SourcesPanel />
      </div>
    </MainLayout>
  );
}
