import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ChatMessage, ChatResponse } from './chat.model';

/**
 * Phase 7 (optional good-to-have): a conversational drill-down over the
 * same attribution/benchmarking/narration services as the rest of the
 * dashboard - "why did on-time arrival drop" gets the identical reasoning
 * the alert feed already uses, just asked for directly instead of waiting
 * for the agent to raise it.
 */
@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.scss',
})
export class ChatComponent {
  draft = '';
  sending = signal(false);
  messages = signal<ChatMessage[]>([]);

  suggestions = [
    'Why did on-time arrival drop this week?',
    'How is Vendor A doing on cost?',
    'Any safety concerns lately?',
    'What is happening in Marathahalli?',
  ];

  constructor(private http: HttpClient) {}

  ask(text?: string): void {
    const question = (text ?? this.draft).trim();
    if (!question || this.sending()) return;

    this.messages.update((list) => [...list, { role: 'user', text: question }]);
    this.draft = '';
    this.sending.set(true);

    this.http.post<ChatResponse>('/api/chat', { message: question }).subscribe({
      next: (res) => {
        this.messages.update((list) => [
          ...list,
          { role: 'agent', text: res.answer, matchedSubject: res.matchedSubject, usedFallback: res.usedFallback },
        ]);
        this.sending.set(false);
      },
      error: () => {
        this.messages.update((list) => [
          ...list,
          { role: 'agent', text: 'Could not reach the backend - try again in a moment.', usedFallback: true },
        ]);
        this.sending.set(false);
      },
    });
  }
}
