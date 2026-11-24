import { canonicalUrl, setCanonical, trackCanonical } from './canonical';

describe('canonical link', () => {
  it('drops trailing slashes but keeps the root', () => {
    expect(canonicalUrl('https://howlers.example', '/')).toBe('https://howlers.example/');
    expect(canonicalUrl('https://howlers.example', '/c/moon/p/12/')).toBe('https://howlers.example/c/moon/p/12');
  });

  it('creates one link element and updates it as the route changes', () => {
    setCanonical('https://howlers.example/a');
    setCanonical('https://howlers.example/b');
    const links = document.head.querySelectorAll('link[rel="canonical"]');
    expect(links).toHaveLength(1);
    expect((links[0] as HTMLLinkElement).href).toBe('https://howlers.example/b');

    let listener: ((s: { location: { pathname: string } }) => void) | null = null;
    const router = {
      state: { location: { pathname: '/c/moon' } },
      subscribe: (fn: typeof listener) => {
        listener = fn;
        return () => undefined;
      },
    };
    trackCanonical(router as never);
    expect((document.head.querySelector('link[rel="canonical"]') as HTMLLinkElement).href).toBe(`${window.location.origin}/c/moon`);
    listener!({ location: { pathname: '/u/mira_k' } });
    expect((document.head.querySelector('link[rel="canonical"]') as HTMLLinkElement).href).toBe(`${window.location.origin}/u/mira_k`);
  });
});
