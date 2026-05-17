import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';
import { getAuthState } from './authStore';

const INITIAL_RECONNECT_MS = 1000;
const MAX_RECONNECT_MS = 30000;

function wsUrl(): string {
  const configured = import.meta.env.VITE_WS_URL as string | undefined;
  if (configured) return configured;
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws`;
}

export function nextReconnectDelay(currentDelayMs: number): number {
  if (!currentDelayMs) return INITIAL_RECONNECT_MS;
  return Math.min(currentDelayMs * 2, MAX_RECONNECT_MS);
}

export function subscribeSeatUpdates(
  eventId: string,
  onUpdate: (message: unknown) => void,
  onStatus?: (status: string) => void
): () => void {
  const token = getAuthState().accessToken;
  if (!token) {
    onStatus?.('Sign in to receive live seat updates.');
    return () => undefined;
  }

  let stopped = false;
  let reconnectDelay = 0;
  let reconnectTimer: number | undefined;
  let client: Client | undefined;
  let subscription: StompSubscription | undefined;

  const scheduleReconnect = () => {
    if (stopped) return;
    reconnectDelay = nextReconnectDelay(reconnectDelay);
    onStatus?.(`Live updates disconnected; reconnecting in ${Math.round(reconnectDelay / 1000)}s.`);
    reconnectTimer = window.setTimeout(connect, reconnectDelay);
  };

  const connect = () => {
    if (stopped) return;
    const currentToken = getAuthState().accessToken;
    if (!currentToken) {
      onStatus?.('Live updates paused until you sign in again.');
      return;
    }
    client = new Client({
      brokerURL: wsUrl(),
      connectHeaders: { Authorization: `Bearer ${currentToken}` },
      reconnectDelay: 0,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      debug: () => undefined,
      onConnect: () => {
        reconnectDelay = 0;
        onStatus?.('Live seat updates connected.');
        onUpdate({ type: 'connected' });
        subscription?.unsubscribe();
        subscription = client?.subscribe(`/topic/events/${eventId}/seats`, (message: IMessage) => {
          try {
            onUpdate(JSON.parse(message.body));
          } catch {
            onUpdate(message.body);
          }
        });
      },
      onStompError: (frame) => onStatus?.(frame.headers.message ?? 'Live updates unavailable.'),
      onWebSocketClose: () => {
        if (!stopped) scheduleReconnect();
      },
    });
    client.activate();
  };

  connect();

  return () => {
    stopped = true;
    if (reconnectTimer) window.clearTimeout(reconnectTimer);
    subscription?.unsubscribe();
    void client?.deactivate();
  };
}
