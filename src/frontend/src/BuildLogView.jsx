/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import { Download, RefreshCw } from "lucide-react";
import { Button } from "./components/ui/button";
import { CardTitle } from "./components/ui/card";
import { duration, startedAt } from "./build-format";

const INLINE_LIMIT = 2 * 1024 * 1024;
/** A rate-limited page is retried after Retry-After; enough retries for a 1 GiB log over 2 MiB pages. */
const MAX_RATE_LIMIT_RETRIES = 6;
const RETRY_FALLBACK_SECONDS = 5;

function sleep(millis) {
  return new Promise((resolve) => setTimeout(resolve, millis));
}

/**
 * Reads the whole log in pages. The public endpoint caps each response, so keep requesting
 * from the next offset until one comes back empty. A rate-limited page is retried from the
 * same offset after Retry-After, so a large anonymous download completes across windows.
 */
export async function copyLog(api, logPath, sink, wait = sleep) {
  let offset = 0;
  let retries = 0;
  for (;;) {
    let response;
    try {
      response = await api(`${logPath}?offset=${offset}`, { raw: true });
    } catch (e) {
      if (e.status !== 429 || retries >= MAX_RATE_LIMIT_RETRIES) throw e;
      retries += 1;
      const seconds = Number(e.retryAfter);
      await wait(
        (Number.isFinite(seconds) && seconds > 0
          ? seconds
          : RETRY_FALLBACK_SECONDS) * 1000,
      );
      continue;
    }
    const reader = response.body.getReader();
    let received = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      received += value.length;
      await sink.write(value);
    }
    offset += received;
    if (received === 0) return;
    // A successful page proves the download is making progress; only bound consecutive 429s.
    retries = 0;
  }
}

/**
 * The suggested download name: project, branch and build identifier, for example
 * `cisys-main-1.log`. Characters that are unsafe in file names, such as the `/` in
 * `feature/x`, become `_`; missing parts are left out.
 */
export function logFileName(projectName, build) {
  return (
    [projectName, build.branch, build.id]
      .filter((part) => part != null && part !== "")
      .map((part) => String(part).replace(/[\\/:*?"<>|\s]+/g, "_"))
      .join("-") + ".log"
  );
}

/**
 * The expanded details and log of one build. Used inline in the signed-in build history
 * and, without the duplicated metadata and inside a scroll box, on the dedicated build page.
 */
/** With `title`, the title and the log actions share one row. */
export function BuildLogPanel({
  api,
  logPath,
  build,
  onRefresh,
  showMeta = true,
  scrollable = false,
  title,
  projectName,
}) {
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [text, setText] = useState("");
  const [more, setMore] = useState(false);
  const [tail, setTail] = useState(false);
  const [error, setError] = useState("");
  const scrollRef = useRef(null);
  // Follow the end of a running log, unless the visitor has scrolled up to read earlier output.
  const stickToBottom = useRef(true);
  // Bytes already shown inline; a refresh asks only for what came after them.
  const offsetRef = useRef(0);
  // Identifies the latest request. A status change can start a load while an earlier one is still
  // pending; only the latest may append text or move the offset, so the next refresh resumes from
  // the accepted response instead of skipping output.
  const requestRef = useRef(0);
  // Kept across appends so a character split at a page boundary is completed by the next page.
  const decoderRef = useRef(new TextDecoder("utf-8"));
  // Mirrors `tail` for the status-change effect, which must not restart from the beginning.
  const tailRef = useRef(false);

  const load = useCallback(
    async ({ reset = true, running = false } = {}) => {
      const request = ++requestRef.current;
      const current = () => request === requestRef.current;
      setLoading(true);
      setError("");
      if (reset) {
        offsetRef.current = 0;
        decoderRef.current = new TextDecoder("utf-8");
        tailRef.current = false;
        setTail(false);
      }
      const startOffset = offsetRef.current;
      // Once the inline view has filled up, a running build switches to the tail so new output stays visible.
      const showTail = !reset && running && startOffset >= INLINE_LIMIT;
      const remaining = INLINE_LIMIT - startOffset;
      if (!showTail && remaining <= 0) {
        setMore(true);
        setLoaded(true);
        setLoading(false);
        return;
      }
      try {
        const params = showTail
          ? new URLSearchParams({ tail: String(INLINE_LIMIT) })
          : new URLSearchParams({
              offset: String(startOffset),
              limit: String(remaining),
            });
        const response = await api(`${logPath}?${params.toString()}`, {
          raw: true,
        });
        if (!current()) {
          await response.body?.cancel().catch(() => {});
          return;
        }
        const size = Number(response.headers.get("X-Log-Size") || 0);
        const reader = response.body.getReader();
        const decoder = showTail
          ? new TextDecoder("utf-8")
          : decoderRef.current;
        let received = 0,
          out = "";
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          if (!current()) {
            await reader.cancel().catch(() => {});
            return;
          }
          received += value.length;
          out += decoder.decode(value, { stream: true });
        }
        if (!current()) return;
        if (showTail) {
          offsetRef.current = Math.max(startOffset, size);
          setText(out);
          tailRef.current = true;
          setTail(true);
          setMore(false);
        } else {
          // Only the accepted response moves the offset; a superseded one must not skip output.
          offsetRef.current = startOffset + received;
          setText((previous) => (reset ? out : previous + out));
          setMore(size > offsetRef.current);
        }
        setLoaded(true);
      } catch (e) {
        if (current()) setError(e.message);
      } finally {
        if (current()) setLoading(false);
      }
    },
    [api, logPath],
  );

  useEffect(() => {
    // When a build finishes while the view follows the tail, keep showing the end of the log.
    if (tailRef.current) void load({ reset: false, running: true });
    else void load();
  }, [load, build.status]);

  useEffect(() => {
    if (!scrollable || build.status !== "RUNNING" || !stickToBottom.current)
      return;
    const element = scrollRef.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [scrollable, build.status, text]);

  function onLogScroll() {
    const element = scrollRef.current;
    if (element)
      stickToBottom.current =
        element.scrollHeight - element.scrollTop - element.clientHeight < 48;
  }

  async function download() {
    setError("");
    const name = logFileName(projectName, build);
    try {
      if (window.showSaveFilePicker) {
        // Ask for the file while the click still counts as a user gesture.
        let handle;
        try {
          handle = await window.showSaveFilePicker({ suggestedName: name });
        } catch (e) {
          if (e.name === "AbortError") return;
          throw e;
        }
        const writable = await handle.createWritable();
        try {
          await copyLog(api, logPath, writable);
          await writable.close();
        } catch (e) {
          // Do not leave a truncated file behind when a page fails, for example on a 429.
          await writable.abort().catch(() => {});
          throw e;
        }
        return;
      }
      const chunks = [];
      await copyLog(api, logPath, {
        write: async (chunk) => {
          chunks.push(chunk);
        },
      });
      const url = URL.createObjectURL(new Blob(chunks, { type: "text/plain" }));
      const a = document.createElement("a");
      a.href = url;
      a.download = name;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (e) {
      setError(e.message);
    }
  }

  async function refresh() {
    await load({ reset: false, running: build.status === "RUNNING" });
    if (onRefresh) await onRefresh();
  }

  return (
    <div className="build-detail">
      {showMeta && (
        <dl className="break-words">
          <dt>Branch</dt>
          <dd>{build.branch ?? "Unknown"}</dd>
          <dt>Commit</dt>
          <dd>{build.revision ?? "Unknown"}</dd>
          <dt>Image</dt>
          <dd>
            {build.image ?? "Unknown"}
            {build.imageDigest ? ` (${build.imageDigest})` : ""}
          </dd>
        </dl>
      )}
      {build.logTruncated && (
        <p className="file-help" role="status">
          The stored output for this build is incomplete or was cut at the size
          limit.
        </p>
      )}
      <div
        className={
          title ? "build-log-actions build-log-header" : "build-log-actions"
        }
      >
        {title && <CardTitle className="mr-auto">{title}</CardTitle>}
        {["QUEUED", "RUNNING"].includes(build.status) && (
          <Button
            type="button"
            variant="outline"
            size="icon-sm"
            onClick={refresh}
            disabled={loading}
            title="Refresh"
            aria-label="Refresh"
          >
            <RefreshCw size={16} aria-hidden="true" />
          </Button>
        )}
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          onClick={download}
          title="Download log"
          aria-label="Download log"
        >
          <Download size={16} aria-hidden="true" />
        </Button>
      </div>
      {error && (
        <p className="file-help" role="alert">
          {error}
        </p>
      )}
      {scrollable ? (
        <div
          className="build-log-scroll"
          ref={scrollRef}
          onScroll={onLogScroll}
          tabIndex={0}
          role="region"
          aria-label="Build log"
        >
          {loading && !loaded ? (
            <p className="file-help">Loading log…</p>
          ) : (
            <pre>{text}</pre>
          )}
        </div>
      ) : loading && !loaded ? (
        <p className="file-help">Loading log…</p>
      ) : (
        <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
          {text}
        </pre>
      )}
      {tail ? (
        <p className="file-help">
          Showing the latest 2 MiB; download the full log.
        </p>
      ) : (
        more && (
          <p className="file-help">
            Showing the first 2 MiB; download the full log.
          </p>
        )
      )}
    </div>
  );
}

/** Collapsible build row for the signed-in build history. The log is loaded the first time it is opened. */
export default function BuildLogView({ api, logPath, build, onRefresh }) {
  const [open, setOpen] = useState(false);
  return (
    <details
      className="build-row"
      onToggle={(e) => setOpen(e.currentTarget.open)}
    >
      <summary>
        #{build.id} · {build.status}
        {build.branch ? ` · ${build.branch}` : ""} · {startedAt(build)} ·{" "}
        {duration(build)}
        {build.revision ? ` · ${build.revision.slice(0, 12)}` : ""}
        {build.exitCode != null ? ` · exit ${build.exitCode}` : ""}
        {build.pinned ? " · pinned" : ""}
      </summary>
      {open && (
        <BuildLogPanel
          api={api}
          logPath={logPath}
          build={build}
          onRefresh={onRefresh}
        />
      )}
    </details>
  );
}
