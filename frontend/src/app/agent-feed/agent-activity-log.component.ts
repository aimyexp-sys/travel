import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { AgentAction, AgentFeedService } from './agent-feed.service';

/**
 * The Angular half of Phase 6's live delivery: one live-updating list
 * covering both the operational alert feed (AUTO_FIRED / LOGGED_INTERNAL -
 * things the agent already acted on) and the activity log of drafted,
 * held escalations (PENDING_APPROVAL - one click away from "sent").
 * History loads once via REST; everything after that arrives over the
 * WebSocket subscription in AgentFeedService.
 */
@Component({
  selector: 'app-agent-activity-log',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './agent-activity-log.component.html',
  styleUrl: './agent-activity-log.component.scss',
})
export class AgentActivityLogComponent implements OnInit {
  busyId: number | null = null;

  constructor(
    public feed: AgentFeedService,
    private http: HttpClient
  ) {}

  ngOnInit(): void {
    this.http.get<unknown[]>('/api/agent/actions?limit=50').subscribe({
      next: (rows) => this.feed.seed(rows),
      error: () => void 0, // the health card already surfaces backend-down state
    });
  }

  runCycleNow(): void {
    this.http.post('/api/agent/run-cycle', {}).subscribe();
  }

  approve(action: AgentAction): void {
    this.busyId = action.id;
    this.http.post(`/api/agent/actions/${action.id}/approve`, {}).subscribe({
      next: () => {
        this.feed.updateStatus(action.id, 'APPROVED');
        this.busyId = null;
      },
      error: () => (this.busyId = null),
    });
  }

  dismiss(action: AgentAction): void {
    this.busyId = action.id;
    this.http.post(`/api/agent/actions/${action.id}/dismiss`, {}).subscribe({
      next: () => {
        this.feed.updateStatus(action.id, 'DISMISSED');
        this.busyId = null;
      },
      error: () => (this.busyId = null),
    });
  }

  statusLabel(status: string): string {
    switch (status) {
      case 'PENDING_APPROVAL': return 'Awaiting approval';
      case 'AUTO_FIRED': return 'Auto-fired (internal alert)';
      case 'LOGGED_INTERNAL': return 'Logged for review';
      case 'APPROVED': return 'Approved - sent (mocked)';
      case 'DISMISSED': return 'Dismissed';
      default: return status;
    }
  }
}
