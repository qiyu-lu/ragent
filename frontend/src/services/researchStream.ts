import { activeResearch, type ResearchRun, type ResearchEvent } from "../types/research";

/** Read-only recovery: a fresh snapshot, then durable events after the last delivered sequence. */
export function subscribeResearchStream(
  after: number,
  callbacks: {
    event: (event: ResearchEvent) => void;
    snapshot: (run: ResearchRun) => void;
    connection: (reconnecting: boolean) => void;
  },
  transport: {
    snapshot: (signal: AbortSignal) => Promise<ResearchRun>;
    open: (cursor: number, signal: AbortSignal) => Promise<Response>;
    idleMs?: number;
    backoffMs?: number;
    random?: () => number;
  }
) {
  const control = new AbortController();
  let cursor = after;
  let settled = false;
  let failures = 0;
  const idleMs = transport.idleMs ?? 15_000;
  const delay = (millis: number) => new Promise<void>((resolve) => {
    const done = () => {
      clearTimeout(timer);
      control.signal.removeEventListener("abort", done);
      resolve();
    };
    const timer = setTimeout(done, millis);
    control.signal.addEventListener("abort", done, { once: true });
    if (control.signal.aborted) done();
  });
  void (async () => {
    while (!control.signal.aborted && !settled) {
      const attempt = new AbortController();
      const abort = () => attempt.abort();
      control.signal.addEventListener("abort", abort, { once: true });
      let watchdog: ReturnType<typeof setTimeout>;
      const touch = () => {
        clearTimeout(watchdog);
        watchdog = setTimeout(abort, idleMs);
      };
      touch();
      try {
        const run = await transport.snapshot(attempt.signal);
        if (control.signal.aborted) break;
        if (attempt.signal.aborted) throw new Error("RESEARCH_SNAPSHOT_TIMEOUT");
        callbacks.snapshot(run);
        settled = !activeResearch(run);
        if (settled) { callbacks.connection(false); break; }
        touch();
        const response = await transport.open(cursor, attempt.signal);
        if (!response.ok || !response.body || !response.headers.get("content-type")?.includes("text/event-stream"))
          throw new Error("RESEARCH_STREAM_UNAVAILABLE");
        callbacks.connection(false);
        const reader = response.body.getReader();
        const abortReader = () => { void reader.cancel().catch(() => undefined); };
        attempt.signal.addEventListener("abort", abortReader, { once: true });
        if (attempt.signal.aborted) abortReader();
        const decoder = new TextDecoder();
        let buffer = "";
        try {
          while (!control.signal.aborted && !attempt.signal.aborted && !settled) {
            const next = await reader.read();
            if (next.done || control.signal.aborted || attempt.signal.aborted) break;
            touch();
            buffer += decoder.decode(next.value, { stream: true });
            buffer = buffer.replace(/\r\n/g, "\n");
            if (buffer.length > 1_048_576) throw new Error("RESEARCH_FRAME_TOO_LARGE");
            let end: number;
            while ((end = buffer.indexOf("\n\n")) >= 0 && !settled) {
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
                failures = 0;
              } else if (name === "progress" || name === "artifact") {
                const event = parsed as ResearchEvent;
                if (Number.isSafeInteger(event.sequence) && event.sequence > cursor) {
                  callbacks.event(event);
                  cursor = event.sequence;
                  failures = 0;
                }
              }
            }
          }
        } finally {
          attempt.signal.removeEventListener("abort", abortReader);
          await reader.cancel().catch(() => undefined);
          reader.releaseLock();
        }
      } catch {
        if (control.signal.aborted) break;
      } finally {
        clearTimeout(watchdog!);
        attempt.abort();
        control.signal.removeEventListener("abort", abort);
      }
      if (!settled && !control.signal.aborted) {
        callbacks.connection(true);
        const base = transport.backoffMs ?? 750;
        const jitter = 0.8 + (transport.random ?? Math.random)() * 0.4;
        await delay(Math.min(15_000, base * 2 ** Math.min(failures++, 5)) * jitter);
      }
    }
  })();
  return () => control.abort();
}
