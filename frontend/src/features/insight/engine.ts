import type { ArgItem, ContextView, DigestLine, DigestView, StanceSource } from '@/types/knowledge';

/** Which engine produced a digest, in reader-facing words. Unknown ids are never presented as AI. */
export function digestEngineLabel(engine: string): { label: string; ai: boolean } {
  if (engine.startsWith('llm')) return { label: 'Summarised by AI', ai: true };
  return { label: 'Extractive summary', ai: false };
}

/** Every comment a digest line cites (digests cached before multi-citation support only have commentId). */
export function lineSources(line: DigestLine): number[] {
  return line.commentIds && line.commentIds.length ? line.commentIds : [line.commentId];
}

/** "Covers 120 of 480 comments" style note, or null when the digest saw the whole thread and has nothing to add. */
export function coverageNote(d: DigestView): string | null {
  const c = d.sections.coverage;
  if (!c) return null;
  if (c.note) return c.note;
  return c.included < c.total ? `Covers ${c.included} of ${c.total} comments.` : null;
}

/** Where a stance came from, with the marker text and tooltip shown on the chip. */
export function stanceOrigin(item: Pick<ArgItem, 'suggested' | 'stanceSource'>): { source: StanceSource; marker: string | null; title: string } {
  const source: StanceSource = item.stanceSource ?? (item.suggested ? 'RULES' : 'READERS');
  switch (source) {
    case 'AI':
      return { source, marker: 'AI-suggested', title: 'Suggested by AI, not yet labelled by people. Use Label to correct it.' };
    case 'RULES':
      return { source, marker: null, title: 'Suggested by rules, not yet labelled by people' };
    default:
      return { source, marker: null, title: 'Labelled by readers' };
  }
}

/** Poll Context Mode while AI stance suggestions are being generated in the background. */
export function contextPollInterval(view: ContextView | undefined): number | false {
  return view?.aiStances?.state === 'PENDING' ? 3000 : false;
}
