import * as React from "react";
import {
  Bot,
  CheckCircle2,
  FlaskConical,
  Loader2,
  Play,
  Radio,
  ShieldCheck,
  Sparkles,
  Square
} from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  approveTaskTemplate,
  cancelRobotMission,
  createTaskTemplate,
  dispatchRobotMission,
  getRobotMission,
  getTaskTemplatesByMessage,
  simulateTaskTemplate
} from "@/services/ironOreService";
import type {
  CandidateTaskTemplate,
  RobotMission,
  SourceRef,
  TaskEvidenceItem,
  TaskEvidenceRef
} from "@/types";

interface IronOreTaskSectionProps {
  messageId: string;
  sources: SourceRef[];
}

const statusText: Record<CandidateTaskTemplate["status"], string> = {
  DRAFT: "待人工批准",
  APPROVED: "已批准",
  SIMULATED: "已模拟"
};

const missionStatusText: Record<RobotMission["status"], string> = {
  READY: "待派发",
  DISPATCHED: "已派发",
  RUNNING: "执行中",
  SUCCEEDED: "已完成",
  FAILED: "执行失败",
  CANCELED: "已取消",
  DISPATCH_FAILED: "派发失败"
};

type TaskAction = "create" | "approve" | "simulate" | "dispatch" | "cancel";

export function IronOreTaskSection({ messageId, sources }: IronOreTaskSectionProps) {
  const xlsxSource = sources.find(
    (source) =>
      source.fileType?.toLowerCase() === "xlsx" || source.docName?.toLowerCase().endsWith(".xlsx")
  );
  const [task, setTask] = React.useState<CandidateTaskTemplate | null>(null);
  const [mission, setMission] = React.useState<RobotMission | null>(null);
  const [loading, setLoading] = React.useState(Boolean(xlsxSource));
  const [action, setAction] = React.useState<TaskAction | null>(null);
  const [loadFailed, setLoadFailed] = React.useState(false);

  React.useEffect(() => {
    if (!xlsxSource) return;
    let cancelled = false;
    setLoading(true);
    getTaskTemplatesByMessage(messageId)
      .then((items) => {
        if (!cancelled) setTask(items.find((item) => item.docId === xlsxSource.docId) ?? null);
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
  }, [messageId, xlsxSource]);

  React.useEffect(() => {
    if (!task) {
      setMission(null);
      return;
    }
    let cancelled = false;
    getRobotMission(task.id)
      .then((item) => {
        if (!cancelled) setMission(item);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [task?.id]);

  React.useEffect(() => {
    if (!task || !mission || !["DISPATCHED", "RUNNING"].includes(mission.status)) return;
    let cancelled = false;
    const timer = window.setInterval(() => {
      getRobotMission(task.id)
        .then((item) => {
          if (!cancelled && item) setMission(item);
        })
        .catch(() => undefined);
    }, 1000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [mission?.status, task?.id]);

  if (!xlsxSource) return null;

  const create = async () => {
    setAction("create");
    try {
      const created = await createTaskTemplate(messageId, xlsxSource.docId);
      setTask(created);
      setLoadFailed(false);
      toast.success("候选任务草案已生成");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "候选任务生成失败");
    } finally {
      setAction(null);
    }
  };

  const approve = async () => {
    if (!task) return;
    setAction("approve");
    try {
      const approved = await approveTaskTemplate(task.id);
      setTask(approved);
      toast.success("候选任务已人工批准");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "批准失败");
    } finally {
      setAction(null);
    }
  };

  const simulate = async () => {
    if (!task) return;
    setAction("simulate");
    try {
      const execution = await simulateTaskTemplate(task.id);
      setTask({ ...task, status: "SIMULATED", execution });
      toast.success("模拟执行完成（未连接真实设备）");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "模拟执行失败");
    } finally {
      setAction(null);
    }
  };

  const dispatch = async () => {
    if (!task) return;
    setAction("dispatch");
    try {
      const dispatched = await dispatchRobotMission(task.id);
      setMission(dispatched);
      toast.success("样品搬运任务已派发到 ROS1 仿真机器人");
    } catch (error) {
      getRobotMission(task.id)
        .then((item) => setMission(item))
        .catch(() => undefined);
      toast.error(error instanceof Error ? error.message : "ROS1 机器人任务派发失败");
    } finally {
      setAction(null);
    }
  };

  const cancel = async () => {
    if (!mission) return;
    setAction("cancel");
    try {
      const cancelled = await cancelRobotMission(mission.id);
      setMission(cancelled);
      toast.success("已请求取消 ROS1 仿真任务");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "取消 ROS1 机器人任务失败");
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
            <p className="text-sm font-medium text-[#334155]">将本次检索证据整理为候选任务</p>
            <p className="mt-0.5 text-xs text-[#64748B]">
              只生成草案；需要人工批准后才能模拟，不连接真实设备。
            </p>
            {loadFailed ? (
              <p className="mt-1 text-xs text-amber-700">历史草案读取失败，可直接重试生成。</p>
            ) : null}
          </div>
          <Button size="sm" variant="outline" onClick={create} disabled={action !== null}>
            {action === "create" ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <Sparkles className="h-3.5 w-3.5" />
            )}
            生成候选任务
          </Button>
        </div>
      </div>
    );
  }

  return (
    <TaskCard
      task={task}
      mission={mission}
      action={action}
      onApprove={approve}
      onSimulate={simulate}
      onDispatch={dispatch}
      onCancel={cancel}
    />
  );
}

interface TaskCardProps {
  task: CandidateTaskTemplate;
  mission: RobotMission | null;
  action: TaskAction | null;
  onApprove: () => void;
  onSimulate: () => void;
  onDispatch: () => void;
  onCancel: () => void;
}

function TaskCard({
  task,
  mission,
  action,
  onApprove,
  onSimulate,
  onDispatch,
  onCancel
}: TaskCardProps) {
  const payload = task.template;
  const missionActive = mission && ["DISPATCHED", "RUNNING"].includes(mission.status);
  const canDispatch = task.status !== "DRAFT" && (!mission || mission.status === "DISPATCH_FAILED");
  return (
    <section className="overflow-hidden rounded-2xl border border-[#D7E3F4] bg-white shadow-sm">
      <div className="border-b border-[#E8EEF6] bg-[#F6F9FD] px-4 py-3">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <div className="flex items-center gap-2 text-xs font-semibold text-[#2563EB]">
              <FlaskConical className="h-4 w-4" />
              候选任务模板 · {statusText[task.status]}
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
                人工批准
              </Button>
            ) : null}
            {task.status === "APPROVED" ? (
              <Button size="sm" onClick={onSimulate} disabled={action !== null}>
                {action === "simulate" ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Play className="h-3.5 w-3.5" />
                )}
                模拟执行
              </Button>
            ) : null}
            {canDispatch ? (
              <Button size="sm" variant="outline" onClick={onDispatch} disabled={action !== null}>
                {action === "dispatch" ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Radio className="h-3.5 w-3.5" />
                )}
                派发 ROS1 搬运仿真
              </Button>
            ) : null}
            {missionActive ? (
              <Button size="sm" variant="outline" onClick={onCancel} disabled={action !== null}>
                {action === "cancel" ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Square className="h-3.5 w-3.5" />
                )}
                取消任务
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

        {task.execution ? (
          <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-3">
            <div className="flex items-center gap-1.5 text-xs font-semibold text-emerald-800">
              <CheckCircle2 className="h-4 w-4" /> 模拟记录（未连接真实设备）
            </div>
            <ol className="mt-2 space-y-1 text-xs text-emerald-900">
              {task.execution.events.map((event) => (
                <li key={`${event.sequence}-${event.type}`}>
                  {event.sequence}. {event.message}
                </li>
              ))}
            </ol>
          </div>
        ) : (
          <p className="text-xs text-amber-700">
            此内容是有来源约束的候选草案，不是已发布规程，也不会直接控制设备。
          </p>
        )}

        {mission ? <RobotMissionPanel mission={mission} /> : null}

        {!mission && task.status !== "DRAFT" ? (
          <p className="text-xs text-[#64748B]">
            ROS1 按钮只派发固定样品搬运子任务：robot-demo-01 将 sample_bucket_01 从 sampling_area
            搬到 center_laboratory；强制 dry-run。
          </p>
        ) : null}
      </div>
    </section>
  );
}

function RobotMissionPanel({ mission }: { mission: RobotMission }) {
  const failed = ["FAILED", "DISPATCH_FAILED"].includes(mission.status);
  const progress = mission.totalSteps > 0 ? (mission.currentStep / mission.totalSteps) * 100 : 0;
  return (
    <div
      className={`rounded-xl border p-3 ${
        failed ? "border-red-200 bg-red-50" : "border-blue-200 bg-blue-50"
      }`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-1.5 text-xs font-semibold text-[#1E3A8A]">
          <Bot className="h-4 w-4" /> ROS1 样品搬运 · {missionStatusText[mission.status]}
        </div>
        <span className="rounded-full bg-white px-2 py-0.5 text-[10px] font-medium text-[#475569]">
          DRY-RUN · 未连接真实设备
        </span>
      </div>
      <p className="mt-1.5 text-xs text-[#334155]">{mission.message}</p>
      <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-white">
        <div
          className="h-full rounded-full bg-[#2563EB] transition-all"
          style={{ width: `${Math.min(100, Math.max(0, progress))}%` }}
        />
      </div>
      <p className="mt-1 text-[11px] text-[#64748B]">
        步骤 {mission.currentStep}/{mission.totalSteps} · 机器人 {mission.robotId} · 指纹
        {` ${mission.planHash.slice(0, 12)}`}
      </p>
      <ol className="mt-2 space-y-1 text-xs text-[#334155]">
        {mission.mission.steps.map((step) => (
          <li key={step.order}>
            {step.order}. {step.skillId}（
            {Object.entries(step.parameters)
              .map(([key, value]) => `${key}=${value}`)
              .join("，")}
            ）
          </li>
        ))}
      </ol>
      {mission.events.length > 0 ? (
        <div className="mt-2 border-t border-blue-200 pt-2 text-[11px] text-[#475569]">
          {mission.events.slice(-4).map((event) => (
            <p key={`${event.sequence}-${event.timestamp}`}>
              {event.sequence}. {event.message}
            </p>
          ))}
        </div>
      ) : null}
    </div>
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
