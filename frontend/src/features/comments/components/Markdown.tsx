import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Link } from 'react-router-dom';

const MENTION = /(^|[\s(])@([A-Za-z0-9_]{3,21})\b/g;

/** Turns @username into internal links before markdown parsing (text-level, no raw HTML). */
export function linkMentions(src: string): string {
  return src.replace(MENTION, (_m, pre: string, name: string) => `${pre}[@${name}](/u/${name})`);
}

const components: Components = {
  a: ({ href, children }) => {
    if (href && href.startsWith('/')) return <Link to={href}>{children}</Link>;
    const safe = href && /^(https?:|mailto:)/i.test(href) ? href : undefined;
    return (
      <a href={safe} target="_blank" rel="noopener noreferrer nofollow ugc">
        {children}
      </a>
    );
  },
  img: ({ alt }) => <span className="text-faint">[image: {alt}]</span>,
};

/** Renders comment markdown. Raw HTML is never rendered (no rehype-raw). */
export function Markdown({ source }: { source: string }) {
  return (
    <div className="prose-howlers prose-compact text-[14.5px]">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components} skipHtml>
        {linkMentions(source)}
      </ReactMarkdown>
    </div>
  );
}
