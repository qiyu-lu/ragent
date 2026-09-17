import { useResearchStore } from "@/stores/researchStore";
import type { ChatMode } from "@/types/research";

export function ChatModeSelector({ disabled = false }: { disabled?: boolean }) {
  const { mode, setMode, knowledgeBases, selectedKbIds, toggleKb, scopeLoaded } =
    useResearchStore();
  return (
    <div className="mb-2 space-y-2">
      <div className="flex gap-1" role="group" aria-label="处理方式">
        {(
          [
            ["QA", "普通问答"],
            ["REPORT", "深入分析"],
            ["PLAN", "生成计划"]
          ] as [ChatMode, string][]
        ).map(([value, label]) => (
          <button
            key={value}
            type="button"
            disabled={disabled}
            aria-pressed={mode === value}
            onClick={() => setMode(value)}
            className={`rounded-lg px-3 py-1.5 text-xs disabled:opacity-50 ${mode === value ? "bg-blue-100 text-blue-700" : "text-slate-500 hover:bg-slate-100"}`}
          >
            {label}
          </button>
        ))}
      </div>
      {mode !== "QA" && (
        <details className="text-xs text-slate-500">
          <summary className="cursor-pointer">
            研究资料范围：已选 {selectedKbIds.length} 个知识库
          </summary>
          <div className="mt-2 flex max-h-32 flex-wrap gap-3 overflow-y-auto">
            {!scopeLoaded && <span>正在加载资料范围…</span>}
            {scopeLoaded && !knowledgeBases.length && <span>请先导入资料到知识库</span>}
            {knowledgeBases.map((kb) => (
              <label key={kb.id} className="flex items-center gap-1.5">
                <input
                  type="checkbox"
                  disabled={disabled}
                  checked={selectedKbIds.includes(kb.id)}
                  onChange={() => toggleKb(kb.id)}
                />
                {kb.name}
              </label>
            ))}
          </div>
        </details>
      )}
    </div>
  );
}
