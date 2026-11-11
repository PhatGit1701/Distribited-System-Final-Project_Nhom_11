export function selectAvailableFile(torrents, selectedId) {
  return torrents.some(file => file.fileId === selectedId)
    ? selectedId : torrents[0]?.fileId ?? null;
}
