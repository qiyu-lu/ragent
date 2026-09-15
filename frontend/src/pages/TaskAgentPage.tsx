import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { ArrowLeft, CheckCircle2, Loader2, Play, RefreshCw } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  taskAgentApi,
  type TaskDocument,
  type TaskRun,
  type TaskSample,
  type TaskStation,
  type TaskStatus,
  type TaskSummary
} from "@/services/taskAgentService";

const labels: Record<TaskStatus, string> = {
  READY: "待继续",
  RUNNING: "执行中",
  WAITING_INPUT: "等待补充",
  WAITING_APPROVAL: "待确认",
  COMPLETED: "已办理",
  CANCELLED: "已取消",
  FAILED: "执行暂停"
};
const fieldClass = "w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm";
const panelClass = "rounded-xl border border-slate-200 bg-white p-5 shadow-sm";

export function TaskAgentPage() {
  const [params, setParams] = useSearchParams();
  const selectedId = params.get("run");
  const [documents, setDocuments] = useState<TaskDocument[]>([]);
  const [samples, setSamples] = useState<TaskSample[]>([]);
  const [stations, setStations] = useState<TaskStation[]>([]);
  const [runs, setRuns] = useState<TaskSummary[]>([]);
  const [run, setRun] = useState<TaskRun | null>(null);
  const [documentId, setDocumentId] = useState("");
  const [sampleId, setSampleId] = useState("");
  const [goal, setGoal] = useState(
    "根据所选送检规程，核对样品资料并选择可用工位，为样品办理送检登记。"
  );
  const [reply, setReply] = useState("");
  const [busy, setBusy] = useState(false);
  const [driving, setDriving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);

  const refresh = useCallback(async () => {
    const [docs, sampleRows, stationRows, taskRows] = await Promise.all([
      taskAgentApi.documents(),
      taskAgentApi.samples(),
      taskAgentApi.stations(),
      taskAgentApi.runs()
    ]);
    setDocuments(docs);
    setSamples(sampleRows);
    setStations(stationRows);
    setRuns(taskRows);
  }, []);

  useEffect(() => {
    refresh().catch((e) => setError(e.message));
  }, [refresh]);
  useEffect(() => {
    let active = true;
    generation.current += 1;
    setBusy(false);
    setDriving(false);
    setRun(null);
    setReply("");
    setError(null);
    if (selectedId)
      taskAgentApi
        .get(selectedId)
        .then((value) => {
          if (active) setRun(value);
        })
        .catch((e) => {
          if (active) setError(e.message);
        });
    return () => {
      active = false;
      generation.current += 1;
    };
  }, [selectedId]);

  const drive = async (id: string) => {
    const token = ++generation.current;
    setBusy(true);
    setDriving(true);
    setError(null);
    try {
      // Each request commits one observation. Leaving this page stops further requests.
      for (let step = 0; step < 18 && token === generation.current; step += 1) {
        const next = await taskAgentApi.advance(id);
        if (token !== generation.current) return;
        setRun(next);
        if (next.status !== "READY") break;
      }
      await refresh();
    } catch (e) {
      if (token === generation.current)
        setError(e instanceof Error ? e.message : "执行请求失败，请刷新任务状态");
    } finally {
      if (token === generation.current) {
        setBusy(false);
        setDriving(false);
      }
    }
  };

  const act = async (operation: () => Promise<unknown>) => {
    setBusy(true);
    setDriving(false);
    setError(null);
    try {
      await operation();
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作失败");
    } finally {
      setBusy(false);
    }
  };

  const currentSample = samples.find((sample) => sample.id === run?.state.sampleId);
  const canReply = run && ["WAITING_INPUT", "WAITING_APPROVAL", "FAILED"].includes(run.status);

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <header className="border-b bg-white px-6 py-4">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-4">
          <div className="flex items-center gap-4">
            <Link to="/chat">
              <Button variant="outline" size="sm">
                <ArrowLeft className="mr-2 h-4 w-4" />
                返回对话
              </Button>
            </Link>
            <div>
              <h1 className="text-lg font-semibold">送检任务助手</h1>
              <p className="text-sm text-slate-500">查规程、核对资料、确认预约与登记</p>
            </div>
          </div>
          <Button
            variant="outline"
            disabled={busy}
            onClick={() =>
              act(async () => {
                await refresh();
                if (selectedId) setRun(await taskAgentApi.get(selectedId));
              })
            }
          >
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        </div>
      </header>
      <main className="mx-auto max-w-7xl space-y-5 p-6">
        {error && (
          <div
            role="alert"
            className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700"
          >
            {error}
          </div>
        )}
        <div className="grid gap-5 lg:grid-cols-[340px_1fr]">
          <aside className="space-y-5">
            <section className={panelClass}>
              <h2 className="mb-4 font-semibold">建立任务</h2>
              <form
                className="space-y-4"
                onSubmit={(event) => {
                  event.preventDefault();
                  act(async () => {
                    const created = await taskAgentApi.start(goal, documentId, sampleId);
                    setParams({ run: created.id });
                  });
                }}
              >
                <label className="block space-y-1 text-sm">
                  <span>办理目标</span>
                  <textarea
                    required
                    maxLength={2000}
                    rows={4}
                    className={fieldClass}
                    value={goal}
                    onChange={(e) => setGoal(e.target.value)}
                  />
                </label>
                <label className="block space-y-1 text-sm">
                  <span>适用规程及版本</span>
                  <select
                    required
                    className={fieldClass}
                    value={documentId}
                    onChange={(e) => setDocumentId(e.target.value)}
                  >
                    <option value="">选择已入库的规程</option>
                    {documents.map((doc) => (
                      <option key={doc.id} value={doc.id}>
                        {doc.name} · {doc.version}
                      </option>
                    ))}
                  </select>
                </label>
                {!documents.length && (
                  <p className="text-xs text-slate-500">请先在知识库中上传规程，等待处理完成。</p>
                )}
                <label className="block space-y-1 text-sm">
                  <span>样品</span>
                  <select
                    required
                    className={fieldClass}
                    value={sampleId}
                    onChange={(e) => setSampleId(e.target.value)}
                  >
                    <option value="">选择待送检样品</option>
                    {samples
                      .filter((sample) => sample.status === "REGISTERED")
                      .map((sample) => (
                        <option key={sample.id} value={sample.id}>
                          {sample.name}
                        </option>
                      ))}
                  </select>
                </label>
                <Button
                  type="submit"
                  disabled={busy || !documentId || !sampleId}
                  className="w-full"
                >
                  建立任务
                </Button>
              </form>
              <Button
                className="mt-3 w-full"
                variant="outline"
                disabled={busy}
                onClick={() =>
                  act(async () => {
                    await taskAgentApi.initialize();
                    toast.success("示例样品和工位已准备好");
                  })
                }
              >
                准备示例样品与工位
              </Button>
              <p className="mt-2 text-xs text-slate-500">
                示例数据供本地业务演示使用；重复准备会保留已有预约和资料。
              </p>
            </section>
            <section className={panelClass}>
              <h2 className="mb-3 font-semibold">最近任务</h2>
              {!runs.length && <p className="text-sm text-slate-500">暂无任务</p>}
              <div className="space-y-2">
                {runs.map((item) => (
                  <button
                    key={item.id}
                    disabled={busy}
                    onClick={() => setParams({ run: item.id })}
                    className={`w-full rounded-lg border p-3 text-left text-sm ${selectedId === item.id ? "border-blue-300 bg-blue-50" : "border-slate-100 hover:bg-slate-50"}`}
                  >
                    <span className="line-clamp-2">{item.goal}</span>
                    <span className="mt-1 block text-xs text-slate-500">
                      {item.sampleId} · {labels[item.status]}
                    </span>
                  </button>
                ))}
              </div>
            </section>
          </aside>
          <div className="space-y-5">
            {!run ? (
              <section className={`${panelClass} py-20 text-center text-slate-500`}>
                建立或选择任务，查看办理进度。
              </section>
            ) : (
              <>
                <section className={panelClass}>
                  <div className="flex items-start justify-between gap-3">
                    <h2 className="font-semibold">{run.state.goal}</h2>
                    <span className="shrink-0 rounded-full bg-blue-50 px-3 py-1 text-sm text-blue-700">
                      {labels[run.status]}
                    </span>
                  </div>
                  <p className="mt-3 text-sm">{run.state.message}</p>
                  <p className="mt-2 text-xs text-slate-500">
                    规程：{run.state.document.name} · {run.state.document.version} · 决策{" "}
                    {run.state.turns}/16 轮
                  </p>
                  {run.status === "RUNNING" && !busy && (
                    <p className="mt-3 text-sm text-amber-700">
                      上一步正在执行，可刷新进度。若服务曾中断，可在{" "}
                      {new Date(run.leaseUntil).toLocaleTimeString()} 后继续。
                    </p>
                  )}
                  <div className="mt-4 flex flex-wrap gap-2">
                    {["READY", "RUNNING"].includes(run.status) && (
                      <Button disabled={busy} onClick={() => drive(run.id)}>
                        {busy ? (
                          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                        ) : (
                          <Play className="mr-2 h-4 w-4" />
                        )}
                        继续执行
                      </Button>
                    )}
                    {!["COMPLETED", "CANCELLED"].includes(run.status) && (
                      <Button
                        variant="outline"
                        disabled={busy && !driving}
                        onClick={() => {
                          generation.current += 1;
                          act(async () => {
                            setRun(await taskAgentApi.cancel(run.id));
                          });
                        }}
                      >
                        取消任务
                      </Button>
                    )}
                  </div>
                </section>
                {currentSample && currentSample.status === "REGISTERED" && (
                  <section className={panelClass}>
                    <h2 className="mb-3 font-semibold">样品资料核对</h2>
                    <p className="mb-3 text-sm text-slate-500">
                      请根据实际资料登记；聊天补充不会自动更改这些状态。
                    </p>
                    <div className="flex flex-wrap gap-5 text-sm">
                      <label className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          disabled={busy}
                          checked={currentSample.labelVerified}
                          onChange={(e) =>
                            act(() =>
                              taskAgentApi.updateSample(
                                currentSample.id,
                                e.target.checked,
                                currentSample.handoffReady
                              )
                            )
                          }
                        />
                        标签已核对
                      </label>
                      <label className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          disabled={busy}
                          checked={currentSample.handoffReady}
                          onChange={(e) =>
                            act(() =>
                              taskAgentApi.updateSample(
                                currentSample.id,
                                currentSample.labelVerified,
                                e.target.checked
                              )
                            )
                          }
                        />
                        交接资料已齐全
                      </label>
                    </div>
                  </section>
                )}
                {run.state.proposal && (
                  <section className={panelClass}>
                    <h2 className="font-semibold">{run.state.proposal.title}</h2>
                    <p className="mt-2 text-sm">
                      拟预约：
                      {stations.find((s) => s.id === run.state.proposal?.stationId)?.name ||
                        run.state.proposal.stationId}
                    </p>
                    <ul className="mt-4 space-y-3 text-sm">
                      {run.state.proposal.requirements.map((item, index) => (
                        <li key={index} className="rounded-lg bg-slate-50 p-3">
                          <p>{item.text}</p>
                          <div className="mt-2 flex flex-wrap gap-2">
                            {item.evidenceIds.map((id) => (
                              <a
                                className="text-xs text-blue-600 underline"
                                key={id}
                                href={`#evidence-${id}`}
                              >
                                查看依据 {id.slice(-6)}
                              </a>
                            ))}
                          </div>
                        </li>
                      ))}
                    </ul>
                    {run.status === "WAITING_APPROVAL" && (
                      <Button
                        className="mt-4"
                        disabled={busy}
                        onClick={() =>
                          act(async () => setRun(await taskAgentApi.approve(run.id, run.revision)))
                        }
                      >
                        <CheckCircle2 className="mr-2 h-4 w-4" />
                        确认预约并创建送检记录
                      </Button>
                    )}
                  </section>
                )}
                {canReply && (
                  <section className={panelClass}>
                    <h2 className="mb-3 font-semibold">补充信息或调整要求</h2>
                    <textarea
                      className={fieldClass}
                      rows={3}
                      maxLength={2000}
                      value={reply}
                      onChange={(e) => setReply(e.target.value)}
                      placeholder="说明补充了哪些资料，或需要调整哪些办理要求"
                    />
                    <Button
                      className="mt-3"
                      disabled={busy || !reply.trim()}
                      onClick={() =>
                        act(async () => {
                          setRun(await taskAgentApi.reply(run.id, reply));
                          setReply("");
                        })
                      }
                    >
                      保存补充
                    </Button>
                  </section>
                )}
                {run.submission && (
                  <section className="rounded-xl border border-emerald-200 bg-emerald-50 p-5">
                    <h2 className="font-semibold text-emerald-800">送检登记已完成</h2>
                    <p className="mt-2 text-sm">
                      样品 {run.submission.sampleId} 已预约工位 {run.submission.stationId}。
                    </p>
                    <p className="mt-2 text-xs text-slate-600">
                      登记编号：{run.submission.runId}。实际检测与样品搬运由线下执行人员安排。
                    </p>
                  </section>
                )}
                <section className={panelClass}>
                  <h2 className="mb-4 font-semibold">办理记录</h2>
                  <ol className="space-y-3">
                    {run.events.map((event) => (
                      <li key={event.sequence} className="border-l-2 border-slate-200 pl-3 text-sm">
                        <p>{event.message}</p>
                        <time className="text-xs text-slate-400">
                          {new Date(event.createdAt).toLocaleString()}
                        </time>
                        {event.detail != null && (
                          <details className="mt-1 text-xs text-slate-500">
                            <summary className="cursor-pointer">查看本步记录</summary>
                            <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-all rounded bg-slate-50 p-2">
                              {JSON.stringify(event.detail, null, 2)}
                            </pre>
                          </details>
                        )}
                      </li>
                    ))}
                  </ol>
                </section>
                {!!run.state.evidence.length && (
                  <section className={panelClass}>
                    <h2 className="mb-4 font-semibold">规程依据</h2>
                    <div className="space-y-3">
                      {run.state.evidence.map((item) => (
                        <details
                          key={item.id}
                          id={`evidence-${item.id}`}
                          className="rounded-lg border p-3 text-sm"
                        >
                          <summary className="cursor-pointer">
                            {item.documentVersion} · {item.sheetName || "正文"}{" "}
                            {item.cellRange || ""} · {item.id.slice(-6)}
                          </summary>
                          <p className="mt-3 whitespace-pre-wrap leading-6">{item.text}</p>
                          <Link
                            className="mt-2 inline-block text-blue-600 underline"
                            to={`/preview/doc/${item.documentId}`}
                            target="_blank"
                          >
                            查看原文
                          </Link>
                        </details>
                      ))}
                    </div>
                  </section>
                )}
              </>
            )}
            <section className={panelClass}>
              <h2 className="mb-3 font-semibold">工位状态</h2>
              <div className="grid gap-3 sm:grid-cols-3">
                {stations.map((station) => (
                  <div className="rounded-lg bg-slate-50 p-3 text-sm" key={station.id}>
                    <p>{station.name}</p>
                    <p className="mt-1 text-xs text-slate-500">
                      {station.testType} ·{" "}
                      {station.reservationRunId
                        ? "已预约"
                        : station.status === "AVAILABLE"
                          ? "可预约"
                          : "不可用"}
                    </p>
                  </div>
                ))}
              </div>
            </section>
          </div>
        </div>
      </main>
    </div>
  );
}
