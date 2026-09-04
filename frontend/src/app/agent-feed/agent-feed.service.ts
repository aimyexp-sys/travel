import { Injectable, signal } from '@angular/core';
import { Client, IMessage } from '@stomp/stompjs';

/** Normalized shape used everywhere in the UI, regardless of whether an
 * action arrived from the REST history endpoint (snake_case DB columns) or
 * a live WebSocket push (camelCase, built fresh by AgentOrchestrator). */
export interface AgentAction {
  id: number;
  findingType: string;
  title: string;
  status: string;
  narrative: string;
  narrationProvider?: string;
  createdAt?: string;
}

/* eslint-disable @typescript-eslint/no-explicit-any */
export function normalizeAction(row: any): AgentAction {
  return {
    id: row.id,
    findingType: row.finding_type ?? row.findingType,
    title: row.title,
    status: row.status,
    narrative: row.narrative,
    narrationProvider: row.narration_provider ?? row.narrationProvider,
    createdAt: row.created_at ?? row.createdAt,
  };
}

/**
 * Phase 6's live-delivery client: connects to the backend's STOMP-over-
 * WebSocket broker (see backend's WebSocketConfig) and subscribes to
 * /topic/agent-actions, so a finding the agent orchestrator just decided on
 * appears here the instant it's persisted - no polling, no refresh. A
 * relative ws(s)://<host>/ws URL means this works unmodified through both
 * nginx's /ws proxy (prod) and ng serve's dev proxy (proxy.conf.json).
 */
@Injectable({ providedIn: 'root' })
export class AgentFeedService {
  connected = signal(false);
  actions = signal<AgentAction[]>([]);

  private client: Client;

  constructor() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${protocol}//${window.location.host}/ws`;

    this.client = new Client({
      brokerURL: url,
      reconnectDelay: 4000,
      onConnect: () => {
        this.connected.set(true);
        this.client.subscribe('/topic/agent-actions', (message: IMessage) => {
          const action = normalizeAction(JSON.parse(message.body));
          this.actions.update((list) =>
            list.some((a) => a.id === action.id) ? list : [action, ...list]
          );
        });
      },
      onWebSocketClose: () => this.connected.set(false),
      onStompError: () => this.connected.set(false),
    });
    this.client.activate();
  }

  /** One-time seed from GET /api/agent/actions so the feed shows history, not just new events. */
  seed(existingRows: unknown[]): void {
    const seeded = existingRows.map(normalizeAction);
    this.actions.update((live) => {
      const liveIds = new Set(live.map((a) => a.id));
      const merged = [...live, ...seeded.filter((a) => !liveIds.has(a.id))];
      merged.sort((a, b) => b.id - a.id);
      return merged;
    });
  }

  updateStatus(id: number, status: string): void {
    this.actions.update((list) => list.map((a) => (a.id === id ? { ...a, status } : a)));
  }
}
