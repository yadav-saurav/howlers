import type { CommunityRef } from '@/types/api';

export function postPath(p: { id: number; community: Pick<CommunityRef, 'slug'> }): string {
  return `/c/${p.community.slug}/p/${p.id}`;
}
