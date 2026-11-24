import { useQuery } from '@tanstack/react-query';
import { http } from '@/services/apiClient';

/** Whether this instance contains the fictional demo data (computed by the API from the rows present). */
export interface DemoStatus {
  demoData: boolean;
  message: string | null;
}

export const demoApi = {
  status: () => http.get<DemoStatus>('/demo/status'),
};

export const demoKeys = { status: ['demo', 'status'] as const };

export const useDemoStatus = () =>
  useQuery({ queryKey: demoKeys.status, queryFn: demoApi.status, staleTime: 30 * 60_000, retry: false });

export const DISMISS_KEY = 'howlers.demoNotice.dismissed';

/** Dismissal is a per-browser convenience; storage may be unavailable (private mode), so failures mean "not dismissed". */
export function isDismissed(storage: Pick<Storage, 'getItem'> | undefined): boolean {
  try {
    return storage?.getItem(DISMISS_KEY) === '1';
  } catch {
    return false;
  }
}

export function rememberDismissed(storage: Pick<Storage, 'setItem'> | undefined): void {
  try {
    storage?.setItem(DISMISS_KEY, '1');
  } catch {
    // ignore: the notice simply shows again next visit
  }
}
