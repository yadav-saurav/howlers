import { http } from '@/services/apiClient';
import type {
  CommunityDetail,
  CommunityMember,
  CommunityRole,
  CommunitySummary,
  Flair,
  OffsetPage,
  Visibility,
} from '@/types/api';

export interface CommunityListParams {
  q?: string;
  topic?: string;
  sort?: 'popular' | 'new';
  page?: number;
  size?: number;
}

export interface CreateCommunityBody {
  slug: string;
  name: string;
  description: string;
  topicId?: number | null;
  visibility: Visibility;
  tags: string[];
  rules: { title: string; description: string }[];
}

const base = (slug: string) => `/communities/${encodeURIComponent(slug)}`;

export const communitiesApi = {
  list: (p: CommunityListParams) => http.get<OffsetPage<CommunitySummary>>('/communities', { ...p }),
  trending: (limit = 5) => http.get<CommunitySummary[]>('/communities/trending', { limit }),
  mine: () => http.get<CommunitySummary[]>('/me/communities'),
  create: (body: CreateCommunityBody) => http.post<CommunityDetail>('/communities', body),
  get: (slug: string) => http.get<CommunityDetail>(base(slug)),
  update: (
    slug: string,
    body: Partial<{ name: string; description: string; topicId: number | null; visibility: Visibility; tags: string[] }>,
  ) => http.patch<CommunityDetail>(base(slug), body),
  putRules: (slug: string, rules: { title: string; description: string }[]) =>
    http.put<CommunityDetail>(`${base(slug)}/rules`, rules),
  putFlairs: (slug: string, flairs: (Omit<Flair, 'id'> & { id?: number })[]) =>
    http.put<CommunityDetail>(`${base(slug)}/flairs`, flairs),
  uploadIcon: (slug: string, file: File) => http.upload<CommunityDetail>(`${base(slug)}/icon`, file),
  uploadBanner: (slug: string, file: File) => http.upload<CommunityDetail>(`${base(slug)}/banner`, file),
  join: (slug: string) => http.post<void>(`${base(slug)}/membership`),
  leave: (slug: string) => http.del<void>(`${base(slug)}/membership`),
  members: (slug: string, page = 0, size = 20) =>
    http.get<OffsetPage<CommunityMember>>(`${base(slug)}/members`, { page, size }),
  setRole: (slug: string, username: string, role: CommunityRole) =>
    http.put<void>(`${base(slug)}/members/${encodeURIComponent(username)}/role`, { role }),
};
