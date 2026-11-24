import { useEffect, type MouseEvent, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

/**
 * Scrolls to a landing-page section and puts its #hash in the address bar without telling the router. A plain
 * `<a href="#faq">` changes the location, so the router's scroll restoration snapped the page back to the top
 * right after the jump (it took several clicks to land).
 */
export function scrollToSection(id: string) {
  const el = document.getElementById(id);
  if (!el) return false;
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  el.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
  window.history.replaceState(window.history.state, '', `#${id}`);
  return true;
}

/** A link to a section of the landing page that works from any page, in one click. */
export function SectionLink({ id, className, children }: { id: string; className?: string; children: ReactNode }) {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const onClick = (e: MouseEvent<HTMLAnchorElement>) => {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return; // new tab etc.: browser default
    e.preventDefault();
    if (pathname === '/' && scrollToSection(id)) return;
    navigate({ pathname: '/', hash: id });
  };
  return <a href={`/#${id}`} className={className} onClick={onClick}>{children}</a>;
}

/** On the landing page: when it is opened with a #hash (e.g. from the footer of another page), go to that section. */
export function useScrollToHashOnLoad() {
  const { hash } = useLocation();
  useEffect(() => {
    if (!hash) return;
    const id = decodeURIComponent(hash.slice(1));
    // Let the sections render and the router's own scroll handling finish first.
    const t = window.setTimeout(() => scrollToSection(id), 120);
    return () => window.clearTimeout(t);
  }, [hash]);
}
