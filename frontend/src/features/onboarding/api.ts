import { http } from '@/services/apiClient';
import type { CommunitySummary, Me, Topic, UserSummary } from '@/types/api';

export const onboardingApi = {
  topics: () => http.get<Topic[]>('/topics'),
  suggestions: (topicIds: number[]) =>
    http.get<{ communities: CommunitySummary[]; users: UserSummary[] }>('/onboarding/suggestions', {
      topicIds: topicIds.join(','),
    }),
  complete: (body: {
    topicIds: number[];
    communitySlugs: string[];
    followUsernames: string[];
    theme?: 'dark' | 'light' | 'system';
  }) => http.post<Me>('/onboarding/complete', body),
  /** Remembered server-side, so onboarding isn't offered again on the next sign-in. */
  skip: () => http.post<Me>('/onboarding/skip'),
};
