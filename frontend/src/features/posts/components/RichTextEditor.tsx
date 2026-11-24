import Link from '@tiptap/extension-link';
import Placeholder from '@tiptap/extension-placeholder';
import { EditorContent, useEditor, type Editor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { Bold, Code, Heading2, Italic, Link2, List, ListOrdered, Quote, Redo2, SquareCode, Strikethrough, Undo2 } from 'lucide-react';
import { useEffect, type ReactNode } from 'react';
import { cn } from '@/utils/format';

function Tool({ label, active, onClick, children, disabled }: { label: string; active?: boolean; onClick: () => void; children: ReactNode; disabled?: boolean }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      aria-pressed={active}
      disabled={disabled}
      onMouseDown={(e) => e.preventDefault()}
      onClick={onClick}
      className={cn(
        'inline-flex h-8 w-8 items-center justify-center rounded-md text-muted hover:bg-surface hover:text-text disabled:opacity-40',
        active && 'bg-surface text-text',
      )}
    >
      {children}
    </button>
  );
}

function Toolbar({ editor }: { editor: Editor }) {
  const setLink = () => {
    const prev = editor.getAttributes('link').href as string | undefined;
    const url = window.prompt('Link URL', prev ?? 'https://');
    if (url === null) return;
    if (!url.trim()) return editor.chain().focus().unsetLink().run();
    if (!/^https?:\/\//i.test(url.trim())) return;
    editor.chain().focus().extendMarkRange('link').setLink({ href: url.trim() }).run();
  };
  return (
    <div role="toolbar" aria-label="Formatting" className="flex flex-wrap gap-0.5 border-b border-border bg-surface-2 px-1.5 py-1">
      <Tool label="Bold" active={editor.isActive('bold')} onClick={() => editor.chain().focus().toggleBold().run()}><Bold size={15} /></Tool>
      <Tool label="Italic" active={editor.isActive('italic')} onClick={() => editor.chain().focus().toggleItalic().run()}><Italic size={15} /></Tool>
      <Tool label="Strikethrough" active={editor.isActive('strike')} onClick={() => editor.chain().focus().toggleStrike().run()}><Strikethrough size={15} /></Tool>
      <Tool label="Inline code" active={editor.isActive('code')} onClick={() => editor.chain().focus().toggleCode().run()}><Code size={15} /></Tool>
      <Tool label="Link" active={editor.isActive('link')} onClick={setLink}><Link2 size={15} /></Tool>
      <span className="mx-1 w-px bg-border" aria-hidden="true" />
      <Tool label="Heading" active={editor.isActive('heading', { level: 2 })} onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}><Heading2 size={15} /></Tool>
      <Tool label="Bulleted list" active={editor.isActive('bulletList')} onClick={() => editor.chain().focus().toggleBulletList().run()}><List size={15} /></Tool>
      <Tool label="Numbered list" active={editor.isActive('orderedList')} onClick={() => editor.chain().focus().toggleOrderedList().run()}><ListOrdered size={15} /></Tool>
      <Tool label="Quote" active={editor.isActive('blockquote')} onClick={() => editor.chain().focus().toggleBlockquote().run()}><Quote size={15} /></Tool>
      <Tool label="Code block" active={editor.isActive('codeBlock')} onClick={() => editor.chain().focus().toggleCodeBlock().run()}><SquareCode size={15} /></Tool>
      <span className="ml-auto" />
      <Tool label="Undo" disabled={!editor.can().undo()} onClick={() => editor.chain().focus().undo().run()}><Undo2 size={15} /></Tool>
      <Tool label="Redo" disabled={!editor.can().redo()} onClick={() => editor.chain().focus().redo().run()}><Redo2 size={15} /></Tool>
    </div>
  );
}

/** TipTap editor producing HTML. The server sanitizes the HTML before storing it. */
export function RichTextEditor({
  value,
  onChange,
  placeholder = 'Share context, details, sources…',
  id,
}: {
  value: string;
  onChange: (html: string) => void;
  placeholder?: string;
  id?: string;
}) {
  const editor = useEditor({
    extensions: [
      StarterKit.configure({ heading: { levels: [2, 3] } }),
      Link.configure({ openOnClick: false, autolink: true, HTMLAttributes: { rel: 'noopener noreferrer nofollow ugc', target: '_blank' } }),
      Placeholder.configure({ placeholder }),
    ],
    content: value,
    editorProps: {
      attributes: { class: 'prose-howlers', 'aria-label': 'Post body', role: 'textbox', 'aria-multiline': 'true', ...(id ? { id } : {}) },
    },
    onUpdate: ({ editor: e }) => onChange(e.isEmpty ? '' : e.getHTML()),
  });

  useEffect(() => {
    if (editor && value === '' && !editor.isEmpty) editor.commands.clearContent();
  }, [value, editor]);

  return (
    <div className="tiptap-editor overflow-hidden rounded-md border border-border bg-surface-2/40 focus-within:border-accent focus-within:ring-2 focus-within:ring-accent/25">
      {editor && <Toolbar editor={editor} />}
      <EditorContent editor={editor} />
    </div>
  );
}
