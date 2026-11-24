import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';
import type { AutomodHit, AutomodPreview, AutomodRule, AutomodRuleInput } from '@/types/automod';

const base = (slug: string) => `/communities/${encodeURIComponent(slug)}/mod/automod`;

export const automodApi = {
  rules: (slug: string) => http.get<AutomodRule[]>(`${base(slug)}/rules`),
  create: (slug: string, r: AutomodRuleInput) => http.post<AutomodRule>(`${base(slug)}/rules`, r),
  update: (slug: string, id: number, r: Partial<AutomodRuleInput>) => http.put<AutomodRule>(`${base(slug)}/rules/${id}`, r),
  remove: (slug: string, id: number) => http.del<void>(`${base(slug)}/rules/${id}`),
  preview: (slug: string, r: AutomodRuleInput) => http.post<AutomodPreview>(`${base(slug)}/preview`, r),
  hits: (slug: string) => http.get<AutomodHit[]>(`${base(slug)}/hits`, { limit: 50 }),
};

export const automodKeys = {
  rules: (slug: string) => ['mod', slug, 'automod', 'rules'] as const,
  hits: (slug: string) => ['mod', slug, 'automod', 'hits'] as const,
};

export const useAutomodRules = (slug: string) =>
  useQuery({ queryKey: automodKeys.rules(slug), queryFn: () => automodApi.rules(slug), retry: false });

export const useAutomodHits = (slug: string) =>
  useQuery({ queryKey: automodKeys.hits(slug), queryFn: () => automodApi.hits(slug), retry: false });

/** Rule writes refresh the rule list and the mod log (every change is logged). */
export function useAutomodMutation<V, R>(slug: string, fn: (v: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: automodKeys.rules(slug) });
      void qc.invalidateQueries({ queryKey: ['mod', slug, 'log'] });
    },
  });
}
