/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Download, RefreshCw } from 'lucide-react';
import { Button } from './components/ui/button';
import { CardTitle } from './components/ui/card';
import { duration, startedAt } from './build-format';

const INLINE_LIMIT = 2 * 1024 * 1024;

/**
 * The expanded details and log of one build. Used inline in the signed-in build history
 * and, without the duplicated metadata and inside a scroll box, on the dedicated build page.
 */
/** With `title`, the title and the log actions share one row. */
export function BuildLogPanel({ api, logPath, build, onRefresh, showMeta = true, scrollable = false, title }) {
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [text, setText] = useState('');
  const [more, setMore] = useState(false);
  const [error, setError] = useState('');
  const scrollRef = useRef(null);
  // Follow the end of a running log, unless the visitor has scrolled up to read earlier output.
  const stickToBottom = useRef(true);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const response = await api(logPath, { raw: true });
      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let received = 0, out = '', cut = false;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) { out += decoder.decode(); break; }
        const room = INLINE_LIMIT - received;
        if (value.length > room) {
          // No final decode, so a character cut at the limit is dropped instead of shown as a replacement character.
          out += decoder.decode(value.subarray(0, room), { stream: true });
          cut = true;
          await reader.cancel();
          break;
        }
        received += value.length;
        out += decoder.decode(value, { stream: true });
      }
      setText(out); setMore(cut); setLoaded(true);
    } catch (e) { setError(e.message); }
    finally { setLoading(false); }
  }, [api, logPath]);

  useEffect(() => { void load(); }, [load, build.status]);

  useEffect(() => {
    if (!scrollable || build.status !== 'RUNNING' || !stickToBottom.current) return;
    const element = scrollRef.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [scrollable, build.status, text]);

  function onLogScroll() {
    const element = scrollRef.current;
    if (element) stickToBottom.current = element.scrollHeight - element.scrollTop - element.clientHeight < 48;
  }

  async function download() {
    setError('');
    const name = `build-${build.id}.log`;
    try {
      if (window.showSaveFilePicker) {
        // Ask for the file while the click still counts as a user gesture.
        let handle;
        try { handle = await window.showSaveFilePicker({ suggestedName: name }); }
        catch (e) { if (e.name === 'AbortError') return; throw e; }
        const response = await api(logPath, { raw: true });
        await response.body.pipeTo(await handle.createWritable());
        return;
      }
      const response = await api(logPath, { raw: true });
      const url = URL.createObjectURL(await response.blob());
      const a = document.createElement('a');
      a.href = url; a.download = name; a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (e) { setError(e.message); }
  }

  async function refresh() { if (onRefresh) await onRefresh(); await load(); }

  return (
    <div className="build-detail">
      {showMeta && <dl className="break-words">
        <dt>Branch</dt>
        <dd>{build.branch ?? 'Unknown'}</dd>
        <dt>Commit</dt>
        <dd>{build.revision ?? 'Unknown'}</dd>
        <dt>Image</dt>
        <dd>{build.image ?? 'Unknown'}{build.imageDigest ? ` (${build.imageDigest})` : ''}</dd>
        <dt>Command</dt>
        <dd><pre className="whitespace-pre-wrap">{build.command ?? 'Unknown'}</pre></dd>
      </dl>}
      {build.logTruncated && <p className="file-help" role="status">The stored output for this build is incomplete or was cut at the size limit.</p>}
      <div className={title ? 'build-log-actions build-log-header' : 'build-log-actions'}>
        {title && <CardTitle className="mr-auto">{title}</CardTitle>}
        {['QUEUED', 'RUNNING'].includes(build.status) && <Button type="button" variant="outline" size="sm" onClick={refresh} disabled={loading}><RefreshCw/> Refresh</Button>}
        <Button type="button" variant="outline" size="icon-sm" onClick={download} title="Download log" aria-label="Download log"><Download size={16} aria-hidden="true"/></Button>
      </div>
      {error && <p className="file-help" role="alert">{error}</p>}
      {scrollable ? (
        <div className="build-log-scroll" ref={scrollRef} onScroll={onLogScroll} tabIndex={0} role="region" aria-label="Build log">
          {loading && !loaded ? <p className="file-help">Loading log…</p> : <pre>{text}</pre>}
        </div>
      ) : loading && !loaded ? <p className="file-help">Loading log…</p>
        : <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{text}</pre>}
      {more && <p className="file-help">Showing the first 2 MiB; download the full log.</p>}
    </div>
  );
}

/** Collapsible build row for the signed-in build history. The log is loaded the first time it is opened. */
export default function BuildLogView({ api, logPath, build, onRefresh }) {
  const [open, setOpen] = useState(false);
  return (
    <details className="build-row" onToggle={(e) => setOpen(e.currentTarget.open)}>
      <summary>
        #{build.id} · {build.status}{build.branch ? ` · ${build.branch}` : ''} · {startedAt(build)} · {duration(build)}
        {build.revision ? ` · ${build.revision.slice(0, 12)}` : ''}{build.exitCode != null ? ` · exit ${build.exitCode}` : ''}{build.pinned ? ' · pinned' : ''}
      </summary>
      {open && <BuildLogPanel api={api} logPath={logPath} build={build} onRefresh={onRefresh}/>}
    </details>
  );
}
