import { http } from '@/services/apiClient';
import type { PushCategory, PushConfig, PushDevice, PushTestResult } from '@/types/push';

export interface SubscriptionBody {
  endpoint: string;
  keys: { p256dh: string; auth: string };
  label?: string;
  categories?: PushCategory[];
}

export const pushApi = {
  config: () => http.get<PushConfig>('/push/config'),
  devices: () => http.get<PushDevice[]>('/me/push/subscriptions'),
  subscribe: (body: SubscriptionBody) => http.post<PushDevice>('/me/push/subscriptions', body),
  update: (id: number, body: { categories?: PushCategory[]; label?: string }) =>
    http.patch<PushDevice>(`/me/push/subscriptions/${id}`, body),
  remove: (id: number) => http.del<void>(`/me/push/subscriptions/${id}`),
  unsubscribeEndpoint: (endpoint: string) => http.post<void>('/me/push/unsubscribe', { endpoint }),
  test: (id: number) => http.post<PushTestResult>(`/me/push/subscriptions/${id}/test`),
};
