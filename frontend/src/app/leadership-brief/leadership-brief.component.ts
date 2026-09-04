import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { LeadershipBrief } from './leadership-brief.model';

/**
 * The transport & facilities head's persona-specific output (Phase 6): a
 * single leadership-ready artifact assembled from a FIXED template, not
 * free-form - no jargon, no raw numbers without SLA/trend context, and
 * "Copy brief" makes it genuinely forward-without-rework, per the build
 * plan's bonus-criteria framing.
 */
@Component({
  selector: 'app-leadership-brief',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './leadership-brief.component.html',
  styleUrl: './leadership-brief.component.scss',
})
export class LeadershipBriefComponent implements OnInit {
  brief = signal<LeadershipBrief | null>(null);
  loading = signal(false);
  copied = signal(false);

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.http.get<LeadershipBrief>('/api/persona/leadership-brief').subscribe({
      next: (b) => {
        this.brief.set(b);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  copyToClipboard(): void {
    const b = this.brief();
    if (!b) return;
    const lines: string[] = [];
    lines.push(`Fleet Operations Brief — as of ${b.periodEnd} (trailing ${b.windowDays} days)`);
    lines.push('');
    lines.push(b.executiveSummary);
    lines.push('');
    lines.push('Top problem areas:');
    for (const p of b.topProblemAreas) {
      lines.push(`- ${p.title}: ${p.narrative}`);
    }
    if (b.wins.length) {
      lines.push('');
      lines.push('Wins:');
      for (const w of b.wins) {
        lines.push(`- ${w}`);
      }
    }
    navigator.clipboard.writeText(lines.join('\n')).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    });
  }
}
