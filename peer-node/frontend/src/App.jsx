import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useNode } from './useNode.js';
import { request } from './api.mjs';
import { chunkDetails, transferStats } from './model.mjs';

function Icon({ name, size = 20, ...props }) {
  const paths = {
    grid: <><rect x="3" y="3" width="7" height="7" rx="1.7"/><rect x="14" y="3" width="7" height="7" rx="1.7"/><rect x="3" y="14" width="7" height="7" rx="1.7"/><rect x="14" y="14" width="7" height="7" rx="1.7"/></>,
    network: <><circle cx="12" cy="5" r="3"/><circle cx="5" cy="18" r="3"/><circle cx="19" cy="18" r="3"/><path d="M10.5 7.5 6.5 15.5M13.5 7.5l4 8M8 18h8"/></>,
    transfer: <><path d="M7 3v17m-4-4 4 4 4-4M17 21V4m-4 4 4-4 4 4"/></>,
    activity: <path d="M2 12h5l3-8 4 16 3-8h5"/>,
    arrow: <path d="m9 5 7 7-7 7"/>,
    download: <><path d="M12 3v12m-5-5 5 5 5-5M4 16v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4"/></>,
    upload: <><path d="M12 16V4M7 9l5-5 5 5M4 16v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4"/></>,
    file: <><path d="M14 2H5a1 1 0 0 0-1 1v18a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1V8zM14 2v6h6M8 13h8M8 17h5"/></>,
    refresh: <><path d="M20 7v5h-5M4 17v-5h5M6.1 6a8 8 0 0 1 13.3 4M4.6 14A8 8 0 0 0 18 18"/></>,
    check: <path d="m5 12 4 4L19 6"/>,
    shield: <><path d="m12 2 8 4v6c0 5-8 10-8 10S4 17 4 12V6z"/><path d="m8 12 3 3 5-6"/></>,
    terminal: <><path d="m4 6 5 5-5 5M12 18h8"/></>,
    close: <path d="m6 6 12 12M6 18 18 6"/>,
    copy: <><rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V3H3v13h5"/></>,
    user: <><circle cx="12" cy="8" r="4"/><path d="M4 22v-3a8 8 0 0 1 16 0v3"/></>,
    plus: <path d="M12 5v14M5 12h14"/>,
    pause: <><path d="M9 5v14M15 5v14"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/></>,
    search: <><circle cx="10" cy="10" r="7"/><path d="m15 15 6 6"/></>,
    info: <><circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7v1"/></>,
    logout: <><path d="M9 3H4v18h5M14 7l5 5-5 5M8 12h11"/></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{paths[name] || paths.grid}</svg>;
}

const statusText = {
  PENDING: 'Pending', DOWNLOADING: 'Downloading', VERIFYING: 'Verifying', COMPLETED: 'Verified',
  FAILED_RETRYING: 'Retrying', QUEUED: 'Queued', FAILED: 'Failed', CANCELLED: 'Cancelled', SEEDING: 'Seeding',
};
const activeStatuses = new Set(['QUEUED', 'DOWNLOADING']);
const exportStatuses = new Set(['COMPLETED', 'SEEDING']);
const bytes = value => {
  if (!Number.isFinite(value)) return '—';
  if (value === 0) return '0 B';
  const index = Math.min(3, Math.floor(Math.log(value) / Math.log(1024)));
  return `${(value / 1024 ** index).toLocaleString(undefined, { maximumFractionDigits: index ? 2 : 0 })} ${['B', 'KiB', 'MiB', 'GiB'][index]}`;
};
const date = value => value ? new Date(value).toLocaleString() : '—';
const time = value => value ? new Date(value).toLocaleTimeString(undefined, { hour12: false }) : '—';
const short = value => value ? `${value.slice(0, 8)}…${value.slice(-5)}` : '—';

function Badge({ status, children, className = '' }) {
  return <span className={`badge status-${String(status || '').toLowerCase()} ${className}`}><i />{children || statusText[status] || status || 'Unknown'}</span>;
}

function Modal({ title, eyebrow, children, onClose, wide = false }) {
  const ref = useRef(null);
  useEffect(() => {
    const previous = document.activeElement;
    ref.current.showModal();
    return () => previous?.focus();
  }, []);
  return <dialog ref={ref} className={`modal ${wide ? 'modal-wide' : ''}`} onCancel={onClose} onClick={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div className="modal-heading"><div><span className="eyebrow">{eyebrow}</span><h2>{title}</h2></div><button type="button" className="icon-button" aria-label="Close dialog" onClick={onClose}><Icon name="close" /></button></div>
    {children}
  </dialog>;
}

function AuthModal({ onClose, authenticate, busy, boundUsername, error }) {
  const [register, setRegister] = useState(false);
  const submit = async event => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    if (await authenticate({ username: String(form.get('username')).trim(), password: form.get('password'), register })) onClose();
  };
  return <Modal title="Connect your node" eyebrow="A place in the swarm" onClose={onClose}>
    <p className="modal-intro">Sign in to the tracker to discover files and exchange verified chunks with other peers.</p>
    <div className="segmented" aria-label="Account action"><button type="button" className={!register ? 'selected' : ''} onClick={() => setRegister(false)}>Sign in</button><button type="button" className={register ? 'selected' : ''} onClick={() => setRegister(true)}>Create account</button></div>
    <form onSubmit={submit} className="form-stack">
      <label>Username<input name="username" autoComplete="username" placeholder="e.g. alice" defaultValue={boundUsername || ''} required minLength={3} maxLength={40} pattern="[a-zA-Z0-9_]{3,40}" title="3–40 letters, digits, or underscores" autoFocus /></label>
      <label>Password<input name="password" type="password" autoComplete={register ? 'new-password' : 'current-password'} placeholder={register ? 'At least 8 characters' : 'Your tracker password'} required minLength={register ? 8 : 1} /></label>
      {error && <p className="form-error" role="alert">{error}</p>}
      <button className="button primary full" disabled={busy === 'session'}>{busy === 'session' ? 'Connecting…' : register ? 'Create account & connect' : 'Sign in & connect'}<Icon name="arrow" size={17} /></button>
    </form>
    <div className="security-note"><Icon name="shield" size={16} /><span>Your session stays on this local node.</span></div>
  </Modal>;
}

function PublishModal({ onClose, publish, busy, onPublished, error }) {
  const [file, setFile] = useState(null);
  const [dragging, setDragging] = useState(false);
  const submit = async event => {
    event.preventDefault();
    if (!file) return;
    const metadata = await publish(file);
    if (metadata) { onPublished(metadata.fileId); onClose(); }
  };
  return <Modal title="Share something useful." eyebrow="Publish to the swarm" onClose={onClose}>
    <p className="modal-intro">The node splits your file into chunks, computes SHA-256 hashes, and announces it to the tracker.</p>
    <form onSubmit={submit} className="form-stack">
      <label className={`drop-zone ${dragging ? 'dragging' : ''}`} onDragOver={event => { event.preventDefault(); setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={event => { event.preventDefault(); setDragging(false); if (event.dataTransfer.files[0]) setFile(event.dataTransfer.files[0]); }}>
        <span className="drop-icon"><Icon name="upload" size={27} /></span>
        <strong>{file ? file.name : 'Drop your file here'}</strong><span>{file ? bytes(file.size) : 'or click to choose a file'}</span>
        <input type="file" aria-label="File to publish" onChange={event => setFile(event.target.files[0] || null)} />
      </label>
      {error && <p className="form-error" role="alert">{error}</p>}
      <button className="button primary full" disabled={!file || busy === 'publish'}>{busy === 'publish' ? 'Hashing & publishing…' : 'Publish file'}<Icon name="upload" size={17} /></button>
    </form>
    <div className="security-note"><Icon name="info" size={16} /><span>Keep this node running so others can download.</span></div>
  </Modal>;
}

function HashValue({ value, label }) {
  const [copied, setCopied] = useState(false);
  return <div className="hash-field"><span>{label}</span><div><code>{value || 'Not available yet'}</code>{value && <button className="icon-button" type="button" aria-label={`Copy ${label}`} onClick={async () => { try { await navigator.clipboard.writeText(value); setCopied(true); setTimeout(() => setCopied(false), 1800); } catch { setCopied(false); } }}><Icon name={copied ? 'check' : 'copy'} size={15} /></button>}</div></div>;
}

function Inspector({ metadata, chunk, index, download, onClose }) {
  const details = chunkDetails(metadata, chunk, index);
  if (!details) return null;
  return <section className="inspector" aria-label={`Chunk ${index} inspector`}>
    <div className="inspector-title"><div><span className="eyebrow">CHUNK INSPECTOR</span><h3>Chunk <span className="mono">{String(index).padStart(3, '0')}</span><Badge status={details.status} /></h3></div><button className="icon-button" type="button" aria-label="Close chunk inspector" onClick={onClose}><Icon name="close" size={17} /></button></div>
    <dl className="detail-grid">
      <div><dt>Byte offset</dt><dd>{details.offset.toLocaleString()} <small>B</small></dd></div>
      <div><dt>Chunk length</dt><dd>{details.length.toLocaleString()} <small>B</small></dd></div>
      <div><dt>Download time</dt><dd>{details.downloadDurationMs || details.status === 'COMPLETED' ? `${details.downloadDurationMs.toLocaleString()} ms` : '—'}</dd></div>
      <div><dt>Source peer</dt><dd>{details.sourceUsername || 'Unassigned'}<small className="block mono">{details.sourcePeerId || '—'}</small></dd></div>
      <div><dt>Endpoint</dt><dd className="mono">{details.sourceEndpoint || '—'}</dd></div>
      <div><dt>Attempts</dt><dd>{details.attemptCount}</dd></div>
    </dl>
    <div className="hash-grid"><HashValue label="Expected SHA-256" value={details.expectedHash} /><HashValue label="Received SHA-256" value={details.sha256Hash} /></div>
    <details className="history" open={details.failoverHistory.length > 0}><summary>Failover history <span>{details.failoverHistory.length} events</span></summary>{details.failoverHistory.length ? <ol>{details.failoverHistory.map((entry, i) => <li key={i}>{typeof entry === 'string' ? entry : JSON.stringify(entry)}</li>)}</ol> : <p>No failovers recorded for this chunk.</p>}</details>
    <div className="inspector-foot"><span>Bytes {details.offset.toLocaleString()}–{details.end.toLocaleString()}</span><span>Transfer started {date(download?.startedAt)}</span>{download?.finishedAt && <span>Finished {date(download.finishedAt)}</span>}</div>
  </section>;
}

function ChunkMatrix({ metadata, download, selectedChunk, setSelectedChunk, session, busy, startDownload, cancelDownload, openAuth, connection }) {
  const [page, setPage] = useState(0);
  useEffect(() => { setPage(0); }, [metadata?.fileId]);
  const chunks = download?.chunks || [];
  const chunkMap = useMemo(() => new Map(chunks.map(chunk => [chunk.chunkIndex, chunk])), [chunks]);
  const stats = metadata ? transferStats(metadata, chunks) : { percent: 0, verifiedChunks: 0, verifiedBytes: 0, activeChunks: 0 };
  const pageSize = 256;
  const pageCount = metadata ? Math.max(1, Math.ceil(metadata.totalChunks / pageSize)) : 1;
  const active = activeStatuses.has(download?.status);
  const completed = exportStatuses.has(download?.status);
  const onKeyDown = (event, index) => {
    const container = event.currentTarget.parentElement;
    const columns = getComputedStyle(container).gridTemplateColumns.split(' ').length;
    const delta = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -columns, ArrowDown: columns }[event.key];
    let target = delta === undefined ? event.key === 'Home' ? 0 : event.key === 'End' ? metadata.totalChunks - 1 : index : index + delta;
    target = Math.max(0, Math.min(metadata.totalChunks - 1, target));
    if (target !== index) {
      event.preventDefault();
      const nextPage = Math.floor(target / pageSize);
      if (nextPage !== page) setPage(nextPage);
      setSelectedChunk(target);
      requestAnimationFrame(() => document.getElementById(`chunk-${target}`)?.focus());
    }
  };
  return <section id="transfers" className="panel matrix-panel">
    <div className="panel-heading"><div className="section-title"><span className="title-icon violet"><Icon name="grid" size={18} /></span><h2>Chunk Matrix</h2><span className={`live-label ${connection === 'live' ? '' : 'not-live'}`}><i />{connection === 'live' ? 'LIVE' : 'SNAPSHOT'}</span></div>{metadata && <span className="muted mono small">{metadata.totalChunks} chunks</span>}</div>
    {metadata ? <>
      <div className="transfer-file"><span className="file-icon"><Icon name="file" /></span><div><h3 title={metadata.fileName}>{metadata.fileName}</h3><p>{bytes(metadata.fileSize)} <span>·</span> {bytes(metadata.chunkSize)} per chunk <span>·</span> <span className="mono">{short(metadata.fileId)}</span></p></div><Badge status={download?.status || 'PENDING'}>{download ? undefined : 'Ready to download'}</Badge></div>
      <div className="transfer-progress"><div><span><strong>{Math.round(stats.percent)}%</strong> verified</span><span className="mono">{stats.verifiedChunks} / {metadata.totalChunks}</span></div><div className="progress-track" role="progressbar" aria-label="Verified chunks" aria-valuenow={Math.round(stats.percent)} aria-valuemin="0" aria-valuemax="100"><span style={{ width: `${stats.percent}%` }} /></div><div className="progress-meta"><span>{bytes(stats.verifiedBytes)} of {bytes(metadata.fileSize)}</span><span>{stats.activeChunks ? `${stats.activeChunks} chunks in flight` : completed ? 'All chunks verified' : active ? 'Waiting for peers' : 'SHA-256 verification'}</span></div></div>
      <div className="matrix-legend">{['COMPLETED', 'DOWNLOADING', 'VERIFYING', 'PENDING', 'FAILED_RETRYING'].map(status => <span key={status}><i className={`chunk-${status.toLowerCase()}`} />{statusText[status]}</span>)}</div>
      <div className="chunk-grid" role="group" aria-label="Numbered chunks. Use arrow keys to move; click or press Enter to inspect.">
        {Array.from({ length: Math.min(pageSize, Math.max(0, metadata.totalChunks - page * pageSize)) }, (_, position) => {
          const index = position + page * pageSize;
          const status = chunkMap.get(index)?.status || 'PENDING';
          return <button key={index} type="button" id={`chunk-${index}`} className={`chunk-cell chunk-${status.toLowerCase()} ${selectedChunk === index ? 'chunk-selected' : ''}`} aria-label={`Chunk ${index}, ${statusText[status] || status}`} aria-pressed={selectedChunk === index} title={`Chunk ${index} · ${statusText[status] || status}`} onClick={() => setSelectedChunk(index)} onKeyDown={event => onKeyDown(event, index)}>{String(index).padStart(2, '0')}</button>;
        })}
        {metadata.totalChunks === 0 && <p className="empty-inline">This empty file has no chunks.</p>}
      </div>
      {pageCount > 1 && <div className="matrix-pagination"><button type="button" className="text-button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button><span>Chunks {page * pageSize}–{Math.min(metadata.totalChunks, (page + 1) * pageSize) - 1}</span><button type="button" className="text-button" disabled={page + 1 >= pageCount} onClick={() => setPage(page + 1)}>Next</button></div>}
      <div className="matrix-help"><Icon name="info" size={14} /><span>Select a chunk to inspect its source, hashes, and transfer history.</span></div>
      {selectedChunk !== null && <Inspector metadata={metadata} chunk={chunkMap.get(selectedChunk)} index={selectedChunk} download={download} onClose={() => setSelectedChunk(null)} />}
      {download?.error && <div className="transfer-error" role="alert"><Icon name="info" size={17} /><span>{download.error}</span></div>}
      <div className="transfer-actions"><span><Icon name="shield" size={14} />Integrity checked, chunk by chunk</span><div>{completed && <a className="button small-button primary" href={`/api/node/files/${encodeURIComponent(metadata.fileId)}`} download><Icon name="download" size={16} />Save file</a>}{active ? <button className="button small-button" disabled={!!busy} onClick={() => cancelDownload(metadata.fileId)}><Icon name="pause" size={15} />Cancel transfer</button> : !completed && <button className="button small-button primary" disabled={!!busy} onClick={() => session ? startDownload(metadata.fileId) : openAuth()}><Icon name="download" size={16} />{busy === metadata.fileId ? 'Starting…' : ['FAILED', 'CANCELLED'].includes(download?.status) ? 'Retry download' : 'Download file'}</button>}</div></div>
    </> : <div className="empty-state matrix-empty"><span className="empty-matrix" aria-hidden="true">{Array.from({ length: 20 }, (_, i) => <i key={i} />)}</span><h3>Every chunk tells a story.</h3><p>Select a file from the directory to follow its journey through the swarm.</p></div>}
  </section>;
}

function Directory({ torrents, downloads, selectedId, setSelectedId, session, onPublish, openAuth, refresh, initializing }) {
  const [query, setQuery] = useState('');
  const filtered = torrents.filter(file => file.fileName.toLowerCase().includes(query.toLowerCase()));
  return <section id="directory" className="panel directory-panel"><div className="panel-heading"><div className="section-title"><span className="title-icon"><Icon name="file" size={18} /></span><h2>File directory</h2><span className="count-pill">{torrents.length}</span></div><button className="icon-button" type="button" aria-label="Refresh directory" onClick={refresh} disabled={!session}><Icon name="refresh" size={17} /></button></div>
    <div className="directory-search"><Icon name="search" size={16} /><input aria-label="Search files" placeholder="Find a file in the swarm…" value={query} onChange={event => setQuery(event.target.value)} /></div>
    <div className="directory-label"><span>NAME / SIZE</span><span>STATE</span></div>
    <div className="file-list">{filtered.map(file => <button type="button" key={file.fileId} className={`file-row ${file.fileId === selectedId ? 'selected' : ''}`} onClick={() => setSelectedId(file.fileId)} aria-pressed={file.fileId === selectedId}><span className="file-icon"><Icon name="file" size={20} /></span><span className="file-info"><strong title={file.fileName}>{file.fileName}</strong><span>{bytes(file.fileSize)} <b>·</b> {file.totalChunks} chunks</span></span><span className={`file-state ${exportStatuses.has(downloads[file.fileId]?.status) ? 'is-seeding' : ''}`}>{exportStatuses.has(downloads[file.fileId]?.status) ? <Icon name="check" size={16} /> : activeStatuses.has(downloads[file.fileId]?.status) ? <Icon name="transfer" size={16} /> : <Icon name="arrow" size={16} />}</span></button>)}{!filtered.length && <div className="empty-state directory-empty"><Icon name="network" size={30} /><h3>{initializing ? 'Finding your node…' : query ? 'No matching files' : session ? 'The swarm is ready.' : 'Join the swarm.'}</h3><p>{query ? 'Try a different file name.' : session ? 'Publish a file or wait for another peer to share one.' : 'Sign in to discover and share files with connected peers.'}</p>{!session && !initializing && <button className="text-button" onClick={openAuth}>Connect your account <Icon name="arrow" size={14} /></button>}</div>}</div>
    <div className="publish-callout"><span className="title-icon violet"><Icon name="upload" size={20} /></span><div><strong>Have something to share?</strong><p>Make a file available to your peers.</p></div><button type="button" className="icon-button publish-plus" aria-label="Publish a file" onClick={session ? onPublish : openAuth}><Icon name="plus" size={20} /></button></div>
  </section>;
}

function PeerTable({ peers, metadata, node, loading, onRefresh }) {
  return <section id="network" className="panel peers-panel"><div className="panel-heading"><div className="section-title"><span className="title-icon teal"><Icon name="network" size={18} /></span><h2>Active peers</h2><span className="count-pill">{peers.length}</span></div><button type="button" className="icon-button" aria-label="Refresh active peers" onClick={onRefresh} disabled={!metadata}><Icon name="refresh" size={17} /></button></div><div className="table-wrap"><table><thead><tr><th>PEER</th><th>ENDPOINT</th><th>AVAILABILITY</th><th>POLICY</th><th>LAST SEEN</th></tr></thead><tbody>{peers.map((peer, index) => {
    const availability = metadata?.totalChunks ? Math.round((peer.availableChunks?.length || 0) / metadata.totalChunks * 100) : 100;
    return <tr key={peer.peerId}><td><div className="peer-name"><span className={`peer-avatar avatar-${index % 3}`}>{(peer.username || peer.peerId || '?').slice(0, 1).toUpperCase()}</span><div><strong>{peer.username || short(peer.peerId)}{node?.peerId === peer.peerId && <span className="you">YOU</span>}</strong><code title={peer.peerId}>{short(peer.peerId)}</code></div></div></td><td className="mono endpoint">{peer.ipAddress}:{peer.tcpPort}</td><td><div className="availability"><div><i style={{ width: `${availability}%` }} /></div><span>{availability}%</span></div></td><td><span className={`policy ${String(peer.titForTatStatus || '').toLowerCase().includes('choke') && !String(peer.titForTatStatus).toLowerCase().includes('unchoke') ? 'policy-choked' : ''}`}>{String(peer.titForTatStatus || 'Available').replaceAll('_', ' ')}</span></td><td className="muted small">{time(peer.lastHeartbeat)}</td></tr>;
  })}{!peers.length && <tr><td colSpan="5"><div className="empty-inline"><Icon name="network" size={19} /><span>{loading ? 'Discovering peers…' : metadata ? 'No peers announced for this file yet.' : 'Select a file to explore its swarm.'}</span></div></td></tr>}</tbody></table></div></section>;
}

function Console({ events, connection, sequence }) {
  const [paused, setPaused] = useState(false);
  const [captured, setCaptured] = useState([]);
  const [filter, setFilter] = useState('ALL');
  const endRef = useRef(null);
  const visible = (paused ? captured : events).filter(event => filter === 'ALL' || event.type === filter);
  useEffect(() => {
    const container = endRef.current?.parentElement;
    if (!paused && container) container.scrollTop = container.scrollHeight;
  }, [events.length, sequence, paused]);
  return <section id="activity" className="panel console-panel"><div className="panel-heading"><div className="section-title"><span className="title-icon"><Icon name="terminal" size={19} /></span><h2>Node console</h2><span className="console-indicator">{connection === 'live' ? 'Streaming' : 'Awaiting connection'}</span></div><div className="console-controls"><select aria-label="Filter console events" value={filter} onChange={event => setFilter(event.target.value)}><option value="ALL">All events</option>{['CHUNK', 'DOWNLOAD', 'LOG', 'HEARTBEAT', 'POLICY'].map(type => <option key={type} value={type}>{type}</option>)}</select><button type="button" className={`icon-button ${paused ? 'is-active' : ''}`} aria-label={paused ? 'Resume console' : 'Pause console'} aria-pressed={paused} onClick={() => { if (!paused) setCaptured(events); setPaused(!paused); }}><Icon name={paused ? 'activity' : 'pause'} size={16} /></button></div></div><div className="console-body" role="log" aria-label="Node event console" aria-live="off">{visible.map(event => <div key={event.sequence} className="console-line"><time>{time(event.time)}</time><span className={`event-type event-${String(event.type).toLowerCase()}`}>{event.type}</span><span>{event.message}</span><span className="event-sequence">#{event.sequence}</span></div>)}{!visible.length && <div className="console-placeholder"><span className="prompt">&gt;</span><span>{events.length ? 'No events match this filter.' : 'Listening for node activity. Your next transfer starts here.'}</span><i /></div>}<div ref={endRef} /></div><div className="console-footer"><span><i className={connection === 'live' ? 'dot teal-dot' : 'dot'} />/topic/node-events</span><span>{paused ? 'Paused' : 'Auto-scroll on'}<b>·</b>Sequence {sequence >= 0 ? sequence : '—'}<b>·</b>Last 250 events</span></div></section>;
}

export function App() {
  const node = useNode();
  const { state, session, initializing, connection, notice, busy } = node;
  const [selectedId, setSelectedId] = useState(null);
  const [selectedChunk, setSelectedChunk] = useState(null);
  const [modal, setModal] = useState(null);
  const [peers, setPeers] = useState([]);
  const [peerLoading, setPeerLoading] = useState(false);
  const [peerRevision, setPeerRevision] = useState(0);
  const [navigation, setNavigation] = useState('Overview');
  const metadata = state.torrents.find(file => file.fileId === selectedId) || state.downloads[selectedId]?.metadata || null;
  const download = state.downloads[selectedId];

  useEffect(() => {
    if ((!selectedId || !state.torrents.some(file => file.fileId === selectedId)) && state.torrents.length) setSelectedId(state.torrents[0].fileId);
  }, [state.torrents, selectedId]);
  useEffect(() => { setSelectedChunk(null); }, [selectedId]);
  useEffect(() => {
    setPeers([]);
    if (!session || !selectedId) return;
    let active = true;
    const controller = new AbortController();
    const load = async () => {
      setPeerLoading(true);
      try {
        const result = await request(`/swarm/${encodeURIComponent(selectedId)}`, { signal: controller.signal });
        if (active) setPeers(result || []);
      } catch (error) { if (active && error.name !== 'AbortError') node.handleError(error); }
      finally { if (active) setPeerLoading(false); }
    };
    load();
    const timer = setInterval(load, 6000);
    return () => { active = false; controller.abort(); clearInterval(timer); };
  }, [selectedId, session?.csrfToken, peerRevision, node.handleError]);

  useEffect(() => {
    if (!session) return;
    const timer = setInterval(() => node.refresh(), 30000);
    return () => clearInterval(timer);
  }, [session?.csrfToken, node.refresh]);

  const downloads = Object.values(state.downloads);
  const verified = downloads.reduce((total, item) => total + (item.chunks || []).filter(chunk => chunk.status === 'COMPLETED').length, 0);
  const active = downloads.filter(item => activeStatuses.has(item.status)).length;
  const heartbeat = state.node?.heartbeat;
  const healthy = heartbeat?.healthy;
  const nodeLabel = state.node?.username || 'Local node';
  const openAuth = () => setModal('auth');
  const refreshAll = async () => { await node.refresh(); setPeerRevision(value => value + 1); };
  const navigate = (label, anchor) => { setNavigation(label); document.getElementById(anchor)?.scrollIntoView({ behavior: 'smooth', block: 'start' }); };

  return <div className="app-shell">
    <aside className="sidebar"><a href="#overview" className="brand" aria-label="Swarm home"><span className="brand-mark"><i/><i/><i/><i/></span><span>swarm<span className="brand-period">.</span></span></a><div className="sidebar-section-label">WORKSPACE</div><nav aria-label="Main navigation">{[['Overview', 'grid', 'overview'], ['File directory', 'file', 'directory'], ['Transfers', 'transfer', 'transfers'], ['Network', 'network', 'network'], ['Activity', 'activity', 'activity']].map(([label, icon, anchor]) => <button type="button" key={label} aria-label={label} className={navigation === label ? 'nav-active' : ''} onClick={() => navigate(label, anchor)}><Icon name={icon} size={19} /><span>{label}</span>{label === 'Transfers' && active > 0 && <b>{active}</b>}</button>)}</nav><div className="sidebar-bottom"><div className="local-badge"><span><Icon name="shield" size={19} /></span><div><strong>Local. Distributed.</strong><p>Your files. Your network.</p></div></div><div className="build-label"><i className="dot teal-dot" />PEER-TO-PEER CONSOLE <span>v1.0</span></div></div></aside>
    <div className="main-shell"><header className="topbar"><div className="breadcrumb">Workspace<Icon name="arrow" size={13} /><strong>{navigation}</strong></div><div className="topbar-right"><span className={`connection-pill ${connection === 'live' ? 'connected' : ''}`}><i />{({ live: 'Live connection', connecting: 'Connecting', syncing: 'Syncing snapshot', reconnecting: 'Reconnecting', offline: 'Not connected' })[connection]}</span><span className="topbar-divider" />{session ? <div className="account"><span className="account-avatar">{nodeLabel.slice(0, 1).toUpperCase()}</span><div><strong>{nodeLabel}</strong><span>Peer node · {state.node?.webPort || location.port || '80'}</span></div><button type="button" className="icon-button" disabled={!!busy} aria-label="Disconnect browser session" title="Disconnect browser session" onClick={node.logout}><Icon name="logout" size={16} /></button></div> : <button type="button" className="button small-button" onClick={openAuth}><Icon name="user" size={16} />Sign in</button>}</div></header>
    <main id="overview"><div className="page-heading"><div><div className="eyebrow"><span className="mini-line" />DISTRIBUTED BY DESIGN</div><h1>Your swarm, <span>in sync.</span></h1><p>A clear view of your files, peers, and every chunk in between.</p></div><div className="heading-actions"><button type="button" className="button" onClick={refreshAll} disabled={!session || !state.snapshotReady}><Icon name="refresh" size={16} />Refresh</button><button type="button" className="button primary" onClick={session ? () => setModal('publish') : openAuth}><Icon name="plus" size={18} />Publish file</button></div></div>
    {notice && <div className={`notice notice-${notice.kind}`} role={notice.kind === 'error' ? 'alert' : 'status'}><Icon name={notice.kind === 'error' ? 'info' : 'check'} size={18} /><span>{notice.text}</span><button type="button" className="icon-button" aria-label="Dismiss notification" onClick={node.dismissNotice}><Icon name="close" size={16} /></button></div>}
    <div className="node-strip"><span className="node-chip"><Icon name="network" size={16} /><strong>{nodeLabel}</strong><code title={state.node?.peerId}>{short(state.node?.peerId)}</code></span><span><i className={`dot ${healthy ? 'teal-dot' : 'amber-dot'}`} /><span title={`${state.node?.trackerUrl || 'Tracker'}\nLast successful heartbeat: ${date(heartbeat?.lastSuccess)}${heartbeat?.error ? `\n${heartbeat.error}` : ''}`}>{healthy ? 'Tracker heartbeat healthy' : session ? 'Awaiting tracker heartbeat' : 'Tracker connection requires sign in'}</span></span><span className="node-port"><span>TCP</span><code>{state.node?.tcpPort || '—'}</code><span className="node-strip-divider"/><span>WEB</span><code>{state.node?.webPort || location.port || '—'}</code></span></div>
    <section className="stats-grid" aria-label="Node statistics">{[
      ['Available files', state.torrents.length, 'In the tracker directory', 'file', 'violet'],
      ['Connected peers', peers.length, metadata ? 'In the selected swarm' : 'Select a file to discover', 'network', 'teal'],
      ['Verified chunks', verified, 'SHA-256 integrity checked', 'shield', 'blue'],
      ['Active transfers', active, active ? 'Chunks are on the move' : 'Ready when you are', 'transfer', 'amber'],
    ].map(([label, value, caption, icon, color]) => <article className="stat-card" key={label}><div><span>{label}</span><span className={`stat-icon ${color}`}><Icon name={icon} size={19} /></span></div><strong>{initializing ? '—' : value.toLocaleString()}</strong><p><i className={`dot ${color === 'teal' || color === 'blue' ? 'teal-dot' : ''}`} />{caption}</p></article>)}</section>
    <div className="workspace-grid"><Directory torrents={state.torrents} downloads={state.downloads} selectedId={selectedId} setSelectedId={setSelectedId} session={session} onPublish={() => setModal('publish')} openAuth={openAuth} refresh={refreshAll} initializing={initializing} /><ChunkMatrix metadata={metadata} download={download} selectedChunk={selectedChunk} setSelectedChunk={setSelectedChunk} session={session} busy={busy} startDownload={node.startDownload} cancelDownload={node.cancelDownload} openAuth={openAuth} connection={connection} /></div>
    <PeerTable peers={peers} metadata={metadata} node={state.node} loading={peerLoading} onRefresh={() => setPeerRevision(value => value + 1)} />
    <Console events={state.events} connection={connection} sequence={state.sequence} />
    <footer className="page-footer"><span><span className="footer-brand">swarm.</span> Small pieces. Stronger together.</span><span><Icon name="shield" size={13} />SHA-256 verified transfers<span className="footer-dot">·</span>Running on your machine</span></footer>
    </main></div>
    {modal === 'auth' && <AuthModal onClose={() => setModal(null)} authenticate={node.authenticate} busy={busy} boundUsername={state.node?.username} error={notice?.kind === 'error' ? notice.text : null} />}
    {modal === 'publish' && <PublishModal onClose={() => setModal(null)} publish={node.publish} busy={busy} onPublished={setSelectedId} error={notice?.kind === 'error' ? notice.text : null} />}
  </div>;
}
