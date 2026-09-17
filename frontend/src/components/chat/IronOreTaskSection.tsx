import * as React from "react";
import { FlaskConical, Loader2, ShieldCheck, Sparkles } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  approveTaskTemplate,
  createTaskTemplate,
  getTaskTemplatesByMessage
} from "@/services/ironOreService";
import type { CandidateTaskTemplate, SourceRef, TaskEvidenceItem, TaskEvidenceRef } from "@/types";

interface IronOreTaskSectionProps {
  messageId: string;
  sources: SourceRef[];
}

const statusText: Record<CandidateTaskTemplate["status"], string> = {
  DRAFT: "待人工确认",
  APPROVED: "已确认"
};

type TaskAction = "create" | "approve";

export function IronOreTaskSection({ messageId, sources }: IronOreTaskSectionProps) {
  const sourceDocId = sources.find(
    (source) =>
      source.fileType?.toLowerCase() === "xlsx" || source.docName?.toLowerCase().endsWith(".xlsx")
  )?.docId;
  const [task, setTask] = React.useState<CandidateTaskTemplate | null>(null);
  const [loading, setLoading] = React.useState(Boolean(sourceDocId));
  const [action, setAction] = React.useState<TaskAction | null>(null);
  const [loadFailed, setLoadFailed] = React.useState(false);

  React.useEffect(() => {
    setTask(null);
    setLoadFailed(false);
    if (!sourceDocId) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    getTaskTemplatesByMessage(messageId)
      .then((items) => {
        if (!cancelled) setTask(items.find((item) => item.docId === sourceDocId) ?? null);
      })
      .catch(() => {
        if (!cancelled) setLoadFailed(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [messageId, sourceDocId]);

  if (!sourceDocId) return null;

  const create = async () => {
    setAction("create");
    try {
      setTask(await createTaskTemplate(messageId, sourceDocId));
      setLoadFailed(false);
      toast.success("计划草稿已生成");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "计划草稿生成失败");
    } finally {
      setAction(null);
    }
  };

  const approve = async () => {
    if (!task) return;
    setAction("approve");
    try {
      setTask(await approveTaskTemplate(task.id));
      toast.success("计划草稿已人工确认");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "确认失败");
    } finally {
      setAction(null);
    }
  };

  if (loading) {
    return <div className="h-9 w-40 animate-pulse rounded-lg bg-[#F3F4F6]" />;
  }

  if (!task) {
    return (
      <div className="rounded-xl border border-dashed border-[#CBD5E1] bg-[#F8FAFC] p-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-sm font-medium text-[#334155]">将本次检索证据整理为计划草稿</p>
            <p className="mt-0.5 text-xs text-[#64748B]">草稿保留来源，缺失要求需进一步核对。</p>
            {loadFailed ? (
              <p className="mt-1 text-xs text-amber-700">历史草稿读取失败，可直接重试生成。</p>
            ) : null}
          </div>
          <Button size="sm" variant="outline" onClick={create} disabled={action !== null}>
            {action === "create" ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <Sparkles className="h-3.5 w-3.5" />
            )}
            生成计划草稿
          </Button>
        </div>
      </div>
    );
  }

  return <TaskCard task={task} action={action} onApprove={approve} />;
}

interface TaskCardProps {
  task: CandidateTaskTemplate;
  action: TaskAction | null;
  onApprove: () => void;
}

function TaskCard({ task, action, onApprove }: TaskCardProps) {
  const payload = task.template;
  return (
    <section className="overflow-hidden rounded-2xl border border-[#D7E3F4] bg-white shadow-sm">
      <div className="border-b border-[#E8EEF6] bg-[#F6F9FD] px-4 py-3">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <div className="flex items-center gap-2 text-xs font-semibold text-[#2563EB]">
              <FlaskConical className="h-4 w-4" />
              计划草稿 · {statusText[task.status]}
            </div>
            <h3 className="mt-1 text-sm font-semibold text-[#172033]">{payload.title}</h3>
            <p className="mt-0.5 text-xs text-[#64748B]">
              {payload.procedureName}
              {payload.documentVersion ? ` · ${payload.documentVersion}` : ""}
            </p>
          </div>
          <div className="flex gap-2">
            {task.status === "DRAFT" ? (
              <Button size="sm" onClick={onApprove} disabled={action !== null}>
                {action === "approve" ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <ShieldCheck className="h-3.5 w-3.5" />
                )}
                人工确认
              </Button>
            ) : null}
          </div>
        </div>
      </div>

      <div className="space-y-4 p-4 text-sm text-[#334155]">
        <EvidenceSection title="前置条件" items={payload.prerequisites} />

        <div>
          <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-[#64748B]">
            步骤
          </h4>
          <ol className="space-y-2">
            {payload.steps.map((step) => (
              <li key={step.order} className="flex gap-2 rounded-lg bg-[#F8FAFC] p-2.5">
                <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#DBEAFE] text-xs font-semibold text-[#2563EB]">
                  {step.order}
                </span>
                <div className="min-w-0">
                  <p>{step.action}</p>
                  {step.parameters.length > 0 ? (
                    <p className="mt-1 text-xs text-[#64748B]">
                      参数：
                      {step.parameters
                        .map((item) => `${item.name}=${item.value}${item.unit ?? ""}`)
                        .join("；")}
                    </p>
                  ) : null}
                  {step.tools.length > 0 ? (
                    <p className="mt-1 text-xs text-[#64748B]">工具：{step.tools.join("、")}</p>
                  ) : null}
                </div>
              </li>
            ))}
          </ol>
        </div>

        <div className="grid gap-3 md:grid-cols-3">
          <EvidenceSection title="质量判据" items={payload.qualityCriteria} compact />
          <EvidenceSection title="异常处理" items={payload.exceptionHandling} compact />
          <EvidenceSection title="安全约束" items={payload.safetyConstraints} compact />
        </div>

        <EvidenceRefs refs={task.evidenceRefs} />

        <p className="text-xs text-amber-700">
          此内容是依据检索片段整理的草稿，请核对来源和缺失项后使用。
        </p>
      </div>
    </section>
  );
}

function EvidenceSection({
  title,
  items,
  compact = false
}: {
  title: string;
  items: TaskEvidenceItem[];
  compact?: boolean;
}) {
  return (
    <div>
      <h4 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-[#64748B]">
        {title}
      </h4>
      {items.length > 0 ? (
        <ul className={compact ? "space-y-1 text-xs" : "list-disc space-y-1 pl-5"}>
          {items.map((item, index) => (
            <li key={`${title}-${index}`}>{item.text}</li>
          ))}
        </ul>
      ) : (
        <p className="text-xs text-[#94A3B8]">文档未提供</p>
      )}
    </div>
  );
}

function EvidenceRefs({ refs }: { refs: TaskEvidenceRef[] }) {
  const locations = Array.from(
    new Set(
      refs
        .map((ref) =>
          [ref.documentVersion, ref.sheetName, ref.cellRange].filter(Boolean).join(" · ")
        )
        .filter(Boolean)
    )
  );
  return (
    <div className="border-t border-[#EDF1F6] pt-3">
      <p className="text-xs font-medium text-[#64748B]">证据位置</p>
      <div className="mt-1.5 flex flex-wrap gap-1.5">
        {locations.map((location) => (
          <span
            key={location}
            className="rounded-full bg-[#EEF2FF] px-2 py-1 text-[11px] text-[#4338CA]"
          >
            {location}
          </span>
        ))}
      </div>
    </div>
  );
}
