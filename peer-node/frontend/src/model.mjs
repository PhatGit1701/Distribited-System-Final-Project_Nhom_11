export const initialState = {
  sequence: -1, generation: 0, snapshotReady: false, node: null,
  torrents: [], downloads: {}, events: [], buffer: [],
};

function consoleEvent(event) {
  return {
    sequence: event.sequence, time: event.time, type: event.type,
    fileId: event.fileId, chunkIndex: event.chunk?.chunkIndex,
    message: event.message || (event.chunk ? `Chunk ${event.chunk.chunkIndex} → ${event.chunk.status}` : event.download ? `Transfer → ${event.download.status}` : event.type),
  };
}

function applyEvent(state, event) {
  if (!Number.isFinite(event?.sequence) || event.sequence <= state.sequence) return state;
  let downloads = state.downloads;
  let torrents = state.torrents;
  const fileId = event.fileId || event.download?.fileId;
  if (event.download && fileId) {
    downloads = { ...downloads, [fileId]: event.download };
    if (event.download.metadata && !torrents.some(item => item.fileId === fileId)) {
      torrents = [...torrents, event.download.metadata];
    }
  }
  if (event.chunk && downloads[fileId]) {
    const existing = downloads[fileId];
    const chunks = [...(existing.chunks || [])];
    const position = chunks.findIndex(item => item.chunkIndex === event.chunk.chunkIndex);
    if (position < 0) chunks.push(event.chunk);
    else chunks[position] = event.chunk;
    downloads = { ...downloads, [fileId]: { ...existing, chunks } };
  }
  return { ...state, sequence: event.sequence, downloads, torrents, events: [...state.events, consoleEvent(event)].slice(-250) };
}

/** A connection generation isolates late HTTP responses and socket callbacks. */
export function reducer(state, action) {
  if (action.type === 'NODE') return { ...state, node: action.node };
  if (action.type === 'DIRECTORY') return { ...state, torrents: action.torrents };
  if (action.type === 'RESET') return { ...initialState, node: action.node || state.node };
  if (action.type === 'SYNC_START') {
    return { ...state, generation: action.generation, snapshotReady: false, buffer: [] };
  }
  if (action.generation !== state.generation) return state;
  if (action.type === 'EVENT') {
    if (!Number.isFinite(action.event?.sequence)) return state;
    if (!state.snapshotReady) return { ...state, buffer: [...state.buffer, action.event] };
    return applyEvent(state, action.event);
  }
  if (action.type === 'SNAPSHOT') {
    const snapshot = action.snapshot;
    if (state.snapshotReady && snapshot.sequence < state.sequence) return state;
    let merged = {
      ...state, sequence: snapshot.sequence, snapshotReady: true,
      node: snapshot.node, torrents: snapshot.torrents || [],
      downloads: Object.fromEntries((snapshot.downloads || []).map(item => [item.fileId, item])),
      events: (snapshot.events || []).map(consoleEvent).slice(-250), buffer: [],
    };
    for (const event of [...state.buffer].sort((a, b) => a.sequence - b.sequence)) merged = applyEvent(merged, event);
    return merged;
  }
  return state;
}

export function chunkDetails(metadata, chunk, index) {
  if (!metadata || !Number.isInteger(index) || index < 0 || index >= metadata.totalChunks) return null;
  const offset = index * metadata.chunkSize;
  const length = Math.min(metadata.chunkSize, metadata.fileSize - offset);
  return {
    chunkIndex: index, status: 'PENDING', sourcePeerId: null, sourceUsername: null,
    sourceEndpoint: null, attemptCount: 0, sha256Hash: null, downloadDurationMs: 0,
    failoverHistory: [], ...chunk,
    expectedHash: chunk?.expectedHash || metadata.chunkHashes?.[index] || null,
    offset, length, end: offset + length - 1,
  };
}

export function transferStats(metadata, chunks = []) {
  let verifiedBytes = 0;
  let verifiedChunks = 0;
  let activeChunks = 0;
  for (const chunk of chunks) {
    if (chunk.status === 'COMPLETED') {
      verifiedChunks++;
      verifiedBytes += Math.max(0, Math.min(metadata.chunkSize, metadata.fileSize - chunk.chunkIndex * metadata.chunkSize));
    }
    if (chunk.status === 'DOWNLOADING' || chunk.status === 'VERIFYING') activeChunks++;
  }
  return { verifiedBytes, verifiedChunks, activeChunks, percent: metadata.fileSize === 0 ? 100 : verifiedBytes / metadata.fileSize * 100 };
}
