import type { ResearchArtifact } from "@/types/research";
import type { SourceRef } from "@/types";
import { SourceCitation } from "@/components/chat/SourceCitation";

export function PlanDraftCard({
  artifact,
  runId,
  sources
}: {
  artifact: ResearchArtifact;
  runId: string;
  sources: SourceRef[];
}) {
  const plan = artifact.plan;
  if (!plan) return null;
  const refs = (ids: string[]) =>
    ids.map((id) => {
      const citation = artifact.citations.find((c) => c.evidenceId === id);
      return citation ? (
        <SourceCitation
          key={id}
          index={citation.index}
          messageId={runId}
          source={sources.find((s) => s.index === citation.index)}
        />
      ) : null;
    });
  const requirements = (title: string, items: { text: string; evidenceIds: string[] }[]) =>
    items.length > 0 && (
      <section>
        <h3 className="font-medium">{title}</h3>
        <ul className="mt-2 list-disc space-y-2 pl-5">
          {items.map((item, i) => (
            <li key={i}>
              {item.text} {refs(item.evidenceIds)}
            </li>
          ))}
        </ul>
      </section>
    );
  return (
    <article
      className="space-y-5 rounded-xl border border-blue-100 bg-blue-50/30 p-5 text-sm"
      aria-label="计划草稿"
    >
      <header>
        <h2 className="text-lg font-semibold">{artifact.title}</h2>
        <p className="mt-1 text-xs text-slate-500">计划草稿 · 可核对资料后调整</p>
      </header>
      <p className="whitespace-pre-wrap">目标：{artifact.goal}</p>
      {artifact.userConstraints.length > 0 && (
        <section>
          <h3 className="font-medium">用户约束</h3>
          <ul className="mt-2 list-disc pl-5">
            {artifact.userConstraints.map((c, i) => (
              <li key={i}>
                {c.text} <span className="text-xs text-slate-500">用户提供</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {requirements("前置条件", plan.prerequisites)}
      {requirements("设备与材料", plan.resources)}
      <section>
        <h3 className="font-medium">步骤</h3>
        {plan.steps.length === 0 && (
          <p className="mt-2 text-slate-500">现有资料不足以整理操作步骤。</p>
        )}
        <ol className="mt-2 space-y-4">
          {plan.steps.map((step) => (
            <li key={step.order} className="rounded-lg bg-white p-3">
              <p>
                {step.order}. {step.action} {refs(step.evidenceIds)}
              </p>
              {step.parameters.length > 0 && (
                <ul className="mt-2 space-y-1 text-slate-600">
                  {step.parameters.map((p, i) => (
                    <li key={i}>
                      {p.name}：
                      {p.value == null || !p.value.trim()
                        ? "待确认"
                        : `${p.value}${p.unit ? ` ${p.unit}` : ""}`}{" "}
                      {refs(p.evidenceIds)}
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ol>
      </section>
      {requirements("注意事项", plan.cautions)}
      {plan.pendingItems.length > 0 && (
        <section>
          <h3 className="font-medium text-amber-700">待确认项</h3>
          <ul className="mt-2 list-disc pl-5">
            {plan.pendingItems.map((p, i) => (
              <li key={i}>{p}</li>
            ))}
          </ul>
        </section>
      )}
      {artifact.gaps.length > 0 && (
        <section>
          <h3 className="font-medium text-amber-700">资料缺口</h3>
          <ul className="mt-2 list-disc pl-5">
            {artifact.gaps.map((g, i) => (
              <li key={i}>{g}</li>
            ))}
          </ul>
        </section>
      )}
      {artifact.conflicts.length > 0 && (
        <section>
          <h3 className="font-medium">资料冲突</h3>
          <ul className="mt-2 list-disc pl-5">
            {artifact.conflicts.map((c, i) => (
              <li key={i}>{c}</li>
            ))}
          </ul>
        </section>
      )}
    </article>
  );
}
