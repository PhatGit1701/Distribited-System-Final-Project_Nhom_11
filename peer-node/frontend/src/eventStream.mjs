export function subscribeToNodeEvents(client, { isActive, onEvent, onSync, onError }) {
  return client.subscribe('/topic/node-events', message => {
    if (!isActive()) return;
    try {
      const event = JSON.parse(message.body);
      if (event.type === 'SYNC') onSync();
      onEvent(event);
    }
    catch { onError(); }
  });
}
