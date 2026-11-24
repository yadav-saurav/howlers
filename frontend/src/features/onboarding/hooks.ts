import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { qk } from '@/services/queryKeys';
import { useAuthStore } from '@/store/authStore';
import { onboardingApi } from './api';

export function useTopics() {
  return useQuery({ queryKey: qk.topics, queryFn: onboardingApi.topics, staleTime: 10 * 60_000 });
}

export function useOnboardingSuggestions(topicIds: number[], enabled: boolean) {
  return useQuery({
    queryKey: qk.onboardingSuggestions(topicIds),
    queryFn: () => onboardingApi.suggestions(topicIds),
    enabled,
  });
}

export function useSkipOnboarding() {
  return useMutation({
    mutationFn: onboardingApi.skip,
    onSuccess: (me) => useAuthStore.getState().setUser(me),
  });
}

export function useCompleteOnboarding() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: onboardingApi.complete,
    onSuccess: (me) => {
      useAuthStore.getState().setUser(me);
      qc.invalidateQueries({ queryKey: qk.myCommunities });
      qc.invalidateQueries({ queryKey: qk.posts });
    },
  });
}
