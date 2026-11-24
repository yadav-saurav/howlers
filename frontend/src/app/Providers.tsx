import { MutationCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MotionConfig } from 'framer-motion';
import { useEffect, useState, type ReactNode } from 'react';
import { TooltipProvider } from '@/components/ui/Tooltip';
import { Toaster } from '@/components/ui/Toast';
import { RealtimeProvider } from '@/features/realtime/RealtimeProvider';
import { PwaRoot } from '@/features/pwa/PwaRoot';
import { ApiError } from '@/services/apiError';
import { refreshSession } from '@/services/apiClient';
import { useAuthStore } from '@/store/authStore';
import { useTheme } from '@/hooks/useTheme';

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: (count, err) => {
          if (err instanceof ApiError && err.status >= 400 && err.status < 500) return false;
          return count < 2;
        },
      },
    },
    mutationCache: new MutationCache(),
  });
}

/** Restores the session from the refresh cookie once on boot. */
function SessionBoot({ children }: { children: ReactNode }) {
  useEffect(() => {
    if (useAuthStore.getState().status === 'booting') void refreshSession();
  }, []);
  // Keep the theme in sync with the signed-in user's saved preference.
  const userTheme = useAuthStore((s) => s.user?.settings.theme);
  useEffect(() => {
    if (userTheme && userTheme !== useTheme.getState().pref) useTheme.getState().setPref(userTheme);
  }, [userTheme]);
  return <>{children}</>;
}

export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(makeClient);
  return (
    <QueryClientProvider client={client}>
      <MotionConfig reducedMotion="user">
        <TooltipProvider>
          <SessionBoot>
            <RealtimeProvider>{children}</RealtimeProvider>
          </SessionBoot>
          <Toaster />
          <PwaRoot />
        </TooltipProvider>
      </MotionConfig>
    </QueryClientProvider>
  );
}
