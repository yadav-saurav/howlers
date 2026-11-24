import { Client, ReconnectionTimeMode, type IFrame, type StompSubscription } from '@stomp/stompjs';
import { create } from 'zustand';
import { useAuthStore } from '@/store/authStore';
import { refreshSession } from './apiClient';

/**
 * Real-time connection: STOMP 1.2 over a plain WebSocket at `/ws` (no SockJS).
 *
 * REST stays the source of truth; frames only tell us what changed. The access token is read fresh before every
 * (re)connect, and an auth rejection triggers a token refresh before the next attempt. Reconnects back off
 * exponentially from 1 s up to 30 s.
 */
export type RealtimeStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting';

export const useRealtimeStore = create<{ status: RealtimeStatus; set: (s: RealtimeStatus) => void }>((set) => ({
  status: 'idle',
  set: (status) => set({ status }),
}));

export const isRealtimeConnected = () => useRealtimeStore.getState().status === 'connected';

type Handler = (body: unknown) => void;
interface TopicEntry {
  handlers: Set<Handler>;
  sub: StompSubscription | null;
}

let client: Client | null = null;
let everConnected = false;
let needsRefresh = false;
const topics = new Map<string, TopicEntry>();

export function brokerUrl(loc: Pick<Location, 'protocol' | 'host'> = window.location): string {
  return `${loc.protocol === 'https:' ? 'wss' : 'ws'}://${loc.host}/ws`;
}

function parse(body: string): unknown {
  try {
    return JSON.parse(body);
  } catch {
    return null;
  }
}

function attach(dest: string, entry: TopicEntry) {
  if (!client?.connected) return;
  entry.sub = client.subscribe(dest, (m) => {
    const body = parse(m.body);
    entry.handlers.forEach((h) => h(body));
  });
}

function setStatus(s: RealtimeStatus) {
  useRealtimeStore.getState().set(s);
}

/** Starts (or restarts) the connection. `onEvent` receives frames from `/user/queue/events`. */
export function startRealtime(onEvent: Handler): void {
  stopRealtime();
  everConnected = false;
  needsRefresh = false;
  const c = new Client({
    brokerURL: brokerUrl(),
    reconnectDelay: 1_000,
    maxReconnectDelay: 30_000,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    heartbeatIncoming: 20_000,
    heartbeatOutgoing: 20_000,
    debug: () => {},
  });
  c.beforeConnect = async () => {
    setStatus(everConnected ? 'reconnecting' : 'connecting');
    if (needsRefresh) {
      needsRefresh = false;
      await refreshSession();
    }
    const token = useAuthStore.getState().accessToken;
    if (!token) {
      // Signed out meanwhile: stop instead of retrying forever.
      void c.deactivate();
      setStatus('idle');
      return;
    }
    c.connectHeaders = { Authorization: `Bearer ${token}` };
  };
  c.onConnect = () => {
    everConnected = true;
    setStatus('connected');
    c.subscribe('/user/queue/events', (m) => onEvent(parse(m.body)));
    topics.forEach((entry, dest) => attach(dest, entry));
  };
  c.onStompError = (frame: IFrame) => {
    // A rejected CONNECT is almost always an expired token; refresh before the next attempt.
    const msg = `${frame.headers.message ?? ''} ${frame.body}`.toLowerCase();
    if (!c.connected || /auth|token|jwt|expired|unauthor/.test(msg)) needsRefresh = true;
  };
  c.onWebSocketClose = () => {
    topics.forEach((e) => (e.sub = null));
    if (c.active) setStatus(everConnected ? 'reconnecting' : 'connecting');
  };
  client = c;
  c.activate();
}

export function stopRealtime(): void {
  const c = client;
  client = null;
  topics.forEach((e) => (e.sub = null));
  if (c) void c.deactivate();
  setStatus('idle');
}

/** Subscribes to a topic for as long as the returned function isn't called; survives reconnects. */
export function subscribeTopic(dest: string, handler: Handler): () => void {
  let entry = topics.get(dest);
  if (!entry) {
    entry = { handlers: new Set(), sub: null };
    topics.set(dest, entry);
    attach(dest, entry);
  }
  entry.handlers.add(handler);
  return () => {
    const e = topics.get(dest);
    if (!e) return;
    e.handlers.delete(handler);
    if (e.handlers.size === 0) {
      topics.delete(dest);
      if (e.sub && client?.connected) e.sub.unsubscribe();
    }
  };
}

/** Sends a frame if connected; returns false otherwise (callers treat realtime sends as best-effort). */
export function publish(dest: string, body: unknown): boolean {
  if (!client?.connected) return false;
  client.publish({ destination: dest, body: JSON.stringify(body) });
  return true;
}
