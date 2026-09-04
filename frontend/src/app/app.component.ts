import { Component, OnInit, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import { AgentActivityLogComponent } from './agent-feed/agent-activity-log.component';
import { LeadershipBriefComponent } from './leadership-brief/leadership-brief.component';

interface HealthResponse {
  status: string;
  service: string;
  database: string;
  timestamp: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, CommonModule, AgentActivityLogComponent, LeadershipBriefComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit {
  title = 'MoveInSync Agentic Mobility Intelligence';
  health = signal<HealthResponse | null>(null);
  error = signal<string | null>(null);

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    // Relative path: nginx (prod) and the dev proxy (ng serve) both forward
    // /api/* to the backend service, so no hardcoded host/port and no CORS.
    this.http.get<HealthResponse>('/api/health').subscribe({
      next: (res) => this.health.set(res),
      error: (err) => this.error.set(err.message || 'Could not reach backend'),
    });
  }
}
