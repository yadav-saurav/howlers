/** "Java, System Design" → ["java", "system-design"], matching the backend tag rule ^[a-z0-9-]{1,24}$. */
export function parseTags(input: string): string[] {
  const out = input
    .split(',')
    .map((t) => t.trim().toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9-]/g, '').slice(0, 24))
    .filter(Boolean);
  return Array.from(new Set(out)).slice(0, 8);
}

/** Move an element within a list (used for drag-and-drop and keyboard reordering). */
export function moveItem<T>(list: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= list.length || to >= list.length) return list;
  const next = list.slice();
  const [x] = next.splice(from, 1);
  next.splice(to, 0, x);
  return next;
}
