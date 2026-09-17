import * as React from "react";
import { toast } from "sonner";
import { useResearchStore } from "@/stores/researchStore";
import { useChatStore } from "@/stores/chatStore";
import { activeResearch } from "@/types/research";
import { researchSources } from "@/services/researchService";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { PlanDraftCard } from "@/components/chat/PlanDraftCard";

const labels = {
  QUEUED: "等待开始",
  RUNNING: "正在研究",
  WAITING_INPUT: "等待补充条件",
  COMPLETED: "研究完成",
  PARTIAL: "已形成部分结果",
  FAILED: "研究未完成",
  CANCELLED: "研究已取消",
  INTERRUPTED: "研究已中断"
};
export function ResearchProgress({ runId }: { runId: string }) {
  const { runs, events, reconnecting, readSources, answer, cancel, regenerate, isSubmitting } =
    useResearchStore();
  const openSources = useChatStore((s) => s.openSourcesPanel);
  const run = runs[runId];
  const [input, setInput] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  if (!run) return null;
  const latest = (events[run.id] ?? []).slice(-5);
  const sources = run.artifact ? researchSources(run) : (readSources[run.id] ?? []);
  const act = async (action: () => Promise<void>) => {
    if (busy) return;
    setBusy(true);
    try {
      await action();
    } catch (failure) {
      toast.error((failure as Error).message || "操作失败");
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="space-y-4" data-research-run={run.id}>
      <div
        className="rounded-xl border border-slate-200 bg-slate-50 p-4 text-sm"
        aria-live="polite"
      >
        <div className="flex items-center justify-between gap-3">
          <span className="font-medium">
            {run.brief.outputType === "PLAN" ? "计划研究" : "深入分析"} · {labels[run.status]}
          </span>
          {!activeResearch(run) && run.status !== "WAITING_INPUT" ? (
            <button
              type="button"
              className="text-xs text-blue-600"
              disabled={busy || isSubmitting}
              onClick={() => void act(() => regenerate(run.id))}
            >
              重新生成
            </button>
          ) : (
            <button
              type="button"
              className="text-xs text-rose-600"
              disabled={busy}
              onClick={() => void act(() => cancel(run.id))}
            >
              取消研究
            </button>
          )}
        </div>
        {reconnecting[run.id] && activeResearch(run) && (
          <p className="mt-2 text-xs text-amber-700">进度连接恢复中，研究继续进行</p>
        )}
        {Object.entries(run.state.subtasks ?? {}).map(([id, task]) => (
          <p key={id} className="mt-2 text-xs text-slate-600">
            {task.task.goal} ·{" "}
            {task.status === "COMPLETED"
              ? "已完成"
              : task.status === "RUNNING"
                ? "研究中"
                : task.status === "QUEUED"
                  ? "排队中"
                  : "已结束"}
          </p>
        ))}
        {activeResearch(run) && (
          <ul className="mt-2 space-y-1 text-xs text-slate-500">
            {latest.map((event) => (
              <li key={event.sequence}>
                {event.taskId === "main" ? "" : "子任务："}
                {event.summary}
              </li>
            ))}
          </ul>
        )}
        {sources.length > 0 && (
          <button
            type="button"
            className="mt-3 text-xs text-blue-600"
            onClick={() => openSources(run.id)}
          >
            查看来源（{sources.length}）
          </button>
        )}
        {run.errorSummary && (
          <p className="mt-2 text-xs text-amber-700">
            {run.artifact ? "研究中有未完成事项，见资料缺口。" : "未能形成完整结果，可重新生成。"}
          </p>
        )}
        {run.status === "WAITING_INPUT" && (
          <form
            className="mt-3 space-y-2"
            onSubmit={(event) => {
              event.preventDefault();
              if (input.trim())
                void act(async () => {
                  await answer(run, input.trim());
                  setInput("");
                });
            }}
          >
            <label className="block font-medium" htmlFor={`research-input-${run.id}`}>
              {run.state.question}
            </label>
            <textarea
              id={`research-input-${run.id}`}
              value={input}
              onChange={(event) => setInput(event.target.value)}
              maxLength={10000}
              className="w-full rounded-lg border p-2"
              placeholder="补充研究所需条件"
            />
            <button
              type="submit"
              disabled={busy || !input.trim()}
              className="rounded-lg bg-blue-600 px-3 py-1.5 text-white disabled:opacity-50"
            >
              继续研究
            </button>
          </form>
        )}
      </div>
      {run.artifact &&
        (run.artifact.outputType === "PLAN" ? (
          <PlanDraftCard artifact={run.artifact} runId={run.id} sources={sources} />
        ) : (
          <MarkdownRenderer content={run.artifact.markdown} messageId={run.id} sources={sources} />
        ))}
    </div>
  );
}
