import type { InfiniteData, QueryClient, QueryKey } from '@tanstack/react-query';
import { qk } from '@/services/queryKeys';
import type { CursorPage, PostDetail, PostSummary } from '@/types/api';

type PostPatch = (p: PostSummary) => PostSummary | null;

/** Every cache root that may hold PostSummary lists. */
const LIST_ROOTS: QueryKey[] = [qk.posts, ['users'], ['search']];

function isInfinite(d: unknown): d is InfiniteData<CursorPage<PostSummary>> {
  return !!d && typeof d === 'object' && Array.isArray((d as InfiniteData<unknown>).pages);
}

function isPost(x: unknown): x is PostSummary {
  return !!x && typeof x === 'object' && 'title' in x && 'community' in x && 'myVote' in x;
}

/** Returns `items` itself when the post isn't in it or the patch returned it unchanged, so untouched lists don't re-render. */
function patchList(items: unknown[], id: number, patch: PostPatch): unknown[] {
  if (!items.some((p) => isPost(p) && p.id === id)) return items;
  let changed = false;
  const out = items.flatMap((p) => {
    if (!isPost(p) || p.id !== id) return [p];
    const next = patch(p);
    if (next !== p) changed = true;
    return next ? [next] : [];
  });
  return changed ? out : items;
}

/**
 * Applies `patch` to a post wherever it is cached (feeds, community lists, trending, saved,
 * profile, search, detail). Returning null removes the post from lists (hide/delete).
 */
export function patchPostEverywhere(qc: QueryClient, id: number, patch: PostPatch) {
  for (const root of LIST_ROOTS) {
    for (const [key, data] of qc.getQueriesData<unknown>({ queryKey: root })) {
      if (isInfinite(data)) {
        let changed = false;
        const pages = data.pages.map((pg) => {
          const items = patchList(pg.items, id, patch);
          if (items === pg.items) return pg;
          changed = true;
          return { ...pg, items };
        });
        if (changed) qc.setQueryData(key, { ...data, pages });
      } else if (Array.isArray(data)) {
        const next = patchList(data, id, patch);
        if (next !== data) qc.setQueryData(key, next);
      }
    }
  }
  const detail = qc.getQueryData<PostDetail>(qk.post(id));
  if (detail) {
    const next = patch(detail);
    if (next && next !== detail) qc.setQueryData<PostDetail>(qk.post(id), { ...detail, ...next, viewer: detail.viewer }); // feed rows carry a narrower viewer
  }
}

export function snapshotPosts(qc: QueryClient) {
  return [...LIST_ROOTS, ['post']].flatMap((root) => qc.getQueriesData<unknown>({ queryKey: root }));
}

export function restoreSnapshot(qc: QueryClient, snap: ReturnType<typeof snapshotPosts>) {
  for (const [key, data] of snap) qc.setQueryData(key, data);
}

/**
 * Drops every page but the first of a cached infinite query and refetches it, so the newest items appear at the top
 * without flashing a loading skeleton. Used by the "N new posts" pill.
 */
export async function refetchFromFirstPage(qc: QueryClient, key: QueryKey) {
  await qc.cancelQueries({ queryKey: key, exact: true });
  qc.setQueryData<InfiniteData<unknown, unknown>>(key, (d) =>
    d && d.pages.length > 1 ? { pages: d.pages.slice(0, 1), pageParams: d.pageParams.slice(0, 1) } : d,
  );
  await qc.refetchQueries({ queryKey: key, exact: true });
}
