import { isRouteErrorResponse, Link, useRouteError } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { BrandMark } from '@/design-system/BrandMark';
import { Mascot } from '@/design-system/Mascot';

export function RouteError() {
  const err = useRouteError();
  const message = isRouteErrorResponse(err)
    ? `${err.status} — ${err.statusText}`
    : err instanceof Error
      ? err.message
      : 'Unexpected error';
  const isChunk = /dynamically imported module|Failed to fetch/i.test(message);
  const notFound = isRouteErrorResponse(err) && err.status === 404;
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 px-6 text-center">
      <BrandMark size={36} />
      <Mascot pose={isChunk ? 'wave' : notFound ? 'sit' : 'sleepy'} size={104} />
      <h1 className="font-serif text-2xl font-semibold">
        {isChunk ? 'Time for a fresh copy' : notFound ? 'This trail goes nowhere' : 'Something broke on this page'}
      </h1>
      <p className="max-w-md text-sm text-muted">
        {isChunk ? 'A new version is available. Reload to continue.' : message}
      </p>
      {!isChunk && !notFound && <p className="max-w-md text-xs text-faint">Even good pups trip sometimes. Reloading usually helps.</p>}
      <div className="flex gap-2">
        <button className={buttonClasses('primary', 'md')} onClick={() => window.location.reload()}>
          Reload
        </button>
        <Link to="/home" className={buttonClasses('secondary', 'md')}>
          Go home
        </Link>
      </div>
    </main>
  );
}
