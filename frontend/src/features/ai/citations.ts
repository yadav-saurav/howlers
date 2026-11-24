/**
 * Turn "[3]" citation markers into in-page links ("#src-<messageId>-3") so react-markdown renders them as
 * anchors we can style and intercept. Markers that don't match a known source are left as plain text.
 */
export function linkCitations(markdown: string, messageId: number, sourceCount: number): string {
  return markdown.replace(/\[(\d{1,3})](?!\()/g, (whole, raw: string) => {
    const n = Number(raw);
    return n >= 1 && n <= sourceCount ? `[${n}](#src-${messageId}-${n})` : whole;
  });
}

export function parseCitationHref(href: string | undefined): { messageId: number; n: number } | null {
  const m = href?.match(/^#src-(\d+)-(\d+)$/);
  return m ? { messageId: Number(m[1]), n: Number(m[2]) } : null;
}
