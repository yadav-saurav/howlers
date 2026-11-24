import { BookOpenCheck, CircleHelp, MessageCircleQuestion, Minus, ThumbsDown, ThumbsUp, UserRound, type LucideIcon } from 'lucide-react';
import type { LineKind, Stance } from '@/types/knowledge';

export const STANCES: Stance[] = ['SUPPORT', 'OPPOSE', 'EVIDENCE', 'EXPERIENCE', 'QUESTION', 'NEUTRAL'];

export const stanceMeta: Record<Stance, { label: string; plural: string; color: string; icon: LucideIcon; hint: string }> = {
  SUPPORT: { label: 'Supporting', plural: 'Supporting arguments', color: '#2ECC94', icon: ThumbsUp, hint: 'Agrees with or strengthens what it replies to' },
  OPPOSE: { label: 'Opposing', plural: 'Counterarguments', color: '#EF6B5C', icon: ThumbsDown, hint: 'Pushes back on what it replies to' },
  EVIDENCE: { label: 'Evidence', plural: 'Evidence & sources', color: '#62AAFF', icon: BookOpenCheck, hint: 'Cites links, data or studies' },
  EXPERIENCE: { label: 'Experience', plural: 'Personal experiences', color: '#E2B238', icon: UserRound, hint: 'First-hand account' },
  QUESTION: { label: 'Question', plural: 'Questions', color: '#A78BFA', icon: MessageCircleQuestion, hint: 'Asks something' },
  NEUTRAL: { label: 'Neutral', plural: 'Other comments', color: '#8A949A', icon: Minus, hint: 'Context or commentary' },
};

export const kindMeta: Record<LineKind, { label: string; tone: string }> = {
  FACT: { label: 'Factual claim', tone: 'text-info' },
  DATA: { label: 'Data', tone: 'text-info' },
  ANECDOTE: { label: 'Anecdote', tone: 'text-warning' },
  OPINION: { label: 'Opinion', tone: 'text-muted' },
  SPECULATION: { label: 'Speculation', tone: 'text-down' },
  STATEMENT: { label: 'Statement', tone: 'text-faint' },
};

export const UnknownIcon = CircleHelp;
