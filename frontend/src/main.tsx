import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { Providers } from '@/app/Providers';
import { router } from '@/app/router';
import { initTheme } from '@/hooks/useTheme';
import { registerServiceWorker } from '@/features/pwa/serviceWorker';
import { trackCanonical } from '@/features/seo/canonical';
import './index.css';

initTheme();
registerServiceWorker();
trackCanonical(router);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Providers>
      <RouterProvider router={router} future={{ v7_startTransition: true }} />
    </Providers>
  </StrictMode>,
);
