/**
 * Keeps <link rel="canonical"> pointing at the current page without its query string or fragment (D-039), so a
 * search engine that renders the SPA indexes /c/x/p/1 once rather than once per ?sort=… variant. Crawlers that don't
 * run JavaScript get the canonical link from the backend's preview document instead.
 */
export function canonicalUrl(origin: string, pathname: string): string {
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, '') : '/';
  return `${origin}${path}`;
}

export function setCanonical(href: string, doc: Document = document) {
  let link = doc.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
  if (!link) {
    link = doc.createElement('link');
    link.rel = 'canonical';
    doc.head.appendChild(link);
  }
  if (link.href !== href) link.href = href;
}

interface RouterLike {
  state: { location: { pathname: string } };
  subscribe(fn: (state: { location: { pathname: string } }) => void): () => void;
}

export function trackCanonical(router: RouterLike) {
  const apply = (pathname: string) => setCanonical(canonicalUrl(window.location.origin, pathname));
  apply(router.state.location.pathname);
  return router.subscribe((s) => apply(s.location.pathname));
}
