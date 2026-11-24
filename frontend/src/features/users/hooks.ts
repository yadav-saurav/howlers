import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { qk } from '@/services/queryKeys';
import { useAuthStore } from '@/store/authStore';
import type { Me, PublicProfile } from '@/types/api';
import { usersApi } from './api';

function useMeUpdater() {
  return (me: Me) => useAuthStore.getState().setUser(me);
}

export function useUpdateMe() {
  const set = useMeUpdater();
  return useMutation({ mutationFn: usersApi.updateMe, onSuccess: set });
}
export function useUpdateSettings() {
  const set = useMeUpdater();
  return useMutation({ mutationFn: usersApi.updateSettings, onSuccess: set });
}
export function useUploadAvatar() {
  const set = useMeUpdater();
  return useMutation({ mutationFn: usersApi.uploadAvatar, onSuccess: set });
}
export function useUploadBanner() {
  const set = useMeUpdater();
  return useMutation({ mutationFn: usersApi.uploadBanner, onSuccess: set });
}
export function useChangePassword() {
  return useMutation({ mutationFn: usersApi.changePassword });
}

export function useProfile(username: string) {
  return useQuery({ queryKey: qk.profile(username), queryFn: () => usersApi.profile(username), enabled: !!username });
}

export function useUserPosts(username: string, enabled = true) {
  return useInfiniteQuery({
    queryKey: qk.userPosts(username),
    queryFn: ({ pageParam }) => usersApi.posts(username, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
  });
}

export function useUserComments(username: string, enabled = true) {
  return useInfiniteQuery({
    queryKey: qk.userComments(username),
    queryFn: ({ pageParam }) => usersApi.comments(username, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
  });
}

export function useFollow(username: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (follow: boolean) => (follow ? usersApi.follow(username) : usersApi.unfollow(username)),
    onMutate: async (follow) => {
      await qc.cancelQueries({ queryKey: qk.profile(username) });
      const prev = qc.getQueryData<PublicProfile>(qk.profile(username));
      if (prev) {
        qc.setQueryData<PublicProfile>(qk.profile(username), {
          ...prev,
          isFollowing: follow,
          followerCount: prev.followerCount + (follow ? 1 : -1),
        });
      }
      return { prev };
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(qk.profile(username), ctx.prev);
    },
    onSettled: () => qc.invalidateQueries({ queryKey: qk.profile(username) }),
  });
}
