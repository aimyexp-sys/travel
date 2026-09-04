export interface ChatMessage {
  role: 'user' | 'agent';
  text: string;
  matchedSubject?: string;
  usedFallback?: boolean;
}

export interface ChatResponse {
  question: string;
  matchedSubject: string;
  vendorId?: string | null;
  zone?: string | null;
  factsSummary?: string;
  answer: string;
  narrationProvider?: string;
  usedFallback: boolean;
}
