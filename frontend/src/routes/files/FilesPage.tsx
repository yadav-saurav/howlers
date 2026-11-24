import { Download, FileText, FolderLock, Link2, LockKeyhole, MoreHorizontal, Pencil, Search, ShieldCheck, Share2, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Badge, Skeleton } from '@/components/ui/Display';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Field, FormAlert, Input } from '@/components/ui/Field';
import { Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from '@/components/ui/Menu';
import { EmptyState, ErrorState, LoadMore } from '@/components/ui/States';
import { Tooltip } from '@/components/ui/Tooltip';
import { toast } from '@/components/ui/Toast';
import {
  fetchOwnContent,
  filesApi,
  openOwnContentResponse,
  saveBlob,
  triggerDownload,
  useFileMutation,
  useFileUsage,
  useFiles,
} from '@/features/files/api';
import { LinksDialog } from '@/features/files/components/LinksDialog';
import { ShareDialog } from '@/features/files/components/ShareDialog';
import { MemoryWarning, StreamingSavePrompt } from '@/features/files/components/StreamingSave';
import { UploadPanel } from '@/features/files/components/UploadPanel';
import { decryptFile, importKey, parseKeyInput, safeFileName } from '@/features/files/crypto';
import { formatBytes, scanSummary, typeLabel, usageFraction } from '@/features/files/helpers';
import { keyStore } from '@/features/files/keyStore';
import { MEMORY_WARNING_BYTES, STREAM_THRESHOLD_BYTES, canStreamToDisk, openContainerStream, responseBytes, type OpenedContainer } from '@/features/files/streamDecrypt';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';
import { errorMessage } from '@/services/apiError';
import type { FileItem } from '@/types/files';
import { cn, formatDate } from '@/utils/format';

function UsageBar() {
  const q = useFileUsage();
  if (!q.data) return <Skeleton className="h-10 w-full sm:w-64" />;
  const frac = usageFraction(q.data.usedBytes, q.data.quotaBytes);
  return (
    <div className="w-full sm:w-64">
      <div className="flex justify-between text-xs text-muted">
        <span>
          {formatBytes(q.data.usedBytes)} of {formatBytes(q.data.quotaBytes)} used
        </span>
        <span>{q.data.fileCount} files</span>
      </div>
      <div
        className="mt-1.5 h-2 overflow-hidden rounded-full bg-surface-2"
        role="meter"
        aria-label="Storage used"
        aria-valuemin={0}
        aria-valuemax={q.data.quotaBytes}
        aria-valuenow={q.data.usedBytes}
      >
        <div className={cn('h-full rounded-full', frac > 0.9 ? 'bg-danger' : frac > 0.75 ? 'bg-warning' : 'bg-brand')} style={{ width: `${Math.max(frac * 100, 1)}%` }} />
      </div>
    </div>
  );
}

function RenameDialog({ file, onClose }: { file: FileItem; onClose: () => void }) {
  const [name, setName] = useState(file.name);
  const save = useFileMutation((n: string) => filesApi.rename(file.id, n));
  return (
    <Dialog
      open
      onOpenChange={(v) => !v && onClose()}
      title={file.e2ee ? 'Rename label' : 'Rename file'}
      size="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            loading={save.isPending}
            disabled={!name.trim()}
            onClick={() => save.mutate(name.trim(), { onSuccess: () => { toast.success('Renamed'); onClose(); } })}
          >
            Save
          </Button>
        </>
      }
    >
      <Field label="Name" hint={file.e2ee ? 'Labels are visible to the server. The real name stays encrypted.' : 'The extension has to stay the same.'}>
        {(p) => (
          // eslint-disable-next-line jsx-a11y/no-autofocus -- dialog opened by the user for this one field; focus belongs in it
          <Input {...p} value={name} onChange={(e) => setName(e.target.value)} maxLength={255} autoFocus />
        )}
      </Field>
      <div className="mt-3"><FormAlert message={save.error ? errorMessage(save.error) : null} /></div>
    </Dialog>
  );
}

function DeleteDialog({ file, onClose }: { file: FileItem; onClose: () => void }) {
  const del = useFileMutation(() => filesApi.remove(file.id));
  return (
    <Dialog
      open
      onOpenChange={(v) => !v && onClose()}
      title="Delete this file?"
      description={file.name}
      size="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            variant="danger"
            loading={del.isPending}
            onClick={() =>
              del.mutate(undefined, {
                onSuccess: () => {
                  keyStore.forget(file.id);
                  toast.success('File deleted');
                  onClose();
                },
                onError: (e) => toast.error(errorMessage(e)),
              })
            }
          >
            Delete
          </Button>
        </>
      }
    >
      <p className="text-sm text-muted">
        Every link to it stops working immediately and the stored copy is removed. This can't be undone.
      </p>
    </Dialog>
  );
}

/** Asks for the key of an E2EE file that this browser doesn't remember, then decrypts locally. */
function KeyPrompt({ file, onKey, onClose }: { file: FileItem; onKey: (key: string) => void; onClose: () => void }) {
  const [text, setText] = useState('');
  const [err, setErr] = useState<string | null>(null);
  return (
    <Dialog
      open
      onOpenChange={(v) => !v && onClose()}
      title="Key needed"
      description={file.name}
      size="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            onClick={() => {
              const k = parseKeyInput(text);
              if (!k) setErr('That doesn’t look like a key or a link with #k=…');
              else onKey(k);
            }}
          >
            Decrypt
          </Button>
        </>
      }
    >
      <p className="mb-3 text-sm text-muted">
        This browser doesn't remember this file's key. Paste the recovery key or a full link to it. Decryption happens here; the key isn't sent anywhere.
      </p>
      <MemoryWarning sizeBytes={file.sizeBytes} className="mb-3" />
      <Field label="Key or link" error={err ?? undefined}>
        {(p) => (
          // eslint-disable-next-line jsx-a11y/no-autofocus -- dialog opened by the user for this one field; focus belongs in it
          <Input {...p} value={text} onChange={(e) => setText(e.target.value)} autoComplete="off" spellCheck={false} autoFocus />
        )}
      </Field>
    </Dialog>
  );
}

type Modal =
  | { kind: 'share'; file: FileItem; key?: string | null }
  | { kind: 'links'; file: FileItem }
  | { kind: 'rename'; file: FileItem }
  | { kind: 'delete'; file: FileItem }
  | { kind: 'key'; file: FileItem }
  | { kind: 'save'; file: FileItem; opened: OpenedContainer };

function FileRow({ file, onOpen, busy, onDownload }: { file: FileItem; onOpen: (m: Modal) => void; busy: boolean; onDownload: () => void }) {
  return (
    <li className="flex items-center gap-3 px-4 py-3 hover:bg-surface-2/50 sm:px-2">
      <span className={cn('inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg', file.e2ee ? 'bg-accent/10 text-accent' : 'bg-surface-2 text-muted')}>
        {file.e2ee ? <LockKeyhole size={16} aria-hidden /> : <FileText size={16} aria-hidden />}
      </span>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium">{file.name}</div>
        <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-faint">
          <span>{formatBytes(file.sizeBytes)}</span>
          <span>{file.e2ee ? 'Encrypted' : typeLabel(file.contentType, file.name)}</span>
          <span>{formatDate(file.createdAt)}</span>
          {file.e2ee ? (
            <Tooltip content="End-to-end encrypted. The server can't read, preview or scan it.">
              <span><Badge tone="info">E2EE · not scanned</Badge></span>
            </Tooltip>
          ) : (
            file.scanStatus === 'CLEAN' && (
              <Tooltip content={file.scanDetail ?? scanSummary(file.scanEngine).text}>
                <span><Badge><ShieldCheck size={10} aria-hidden /> {scanSummary(file.scanEngine).badge}</Badge></span>
              </Tooltip>
            )
          )}
          {file.activeLinks > 0 && (
            <button type="button" onClick={() => onOpen({ kind: 'links', file })} className="inline-flex items-center gap-1 text-brand hover:underline">
              <Link2 size={11} aria-hidden /> {file.activeLinks} active {file.activeLinks === 1 ? 'link' : 'links'}
            </button>
          )}
        </div>
      </div>
      <Button variant="ghost" size="sm" className="hidden sm:inline-flex" loading={busy} onClick={onDownload} aria-label={`Download ${file.name}`}>
        <Download size={14} />
      </Button>
      <Button variant="secondary" size="sm" onClick={() => onOpen({ kind: 'share', file })} aria-label={`Share ${file.name}`}>
        <Share2 size={14} /> <span className="hidden sm:inline">Share</span>
      </Button>
      <Menu>
        <MenuTrigger aria-label={`More actions for ${file.name}`} className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full hover:bg-surface-2">
          <MoreHorizontal size={16} />
        </MenuTrigger>
        <MenuContent>
          <MenuItem icon={<Download />} onSelect={onDownload}>{file.e2ee ? 'Download and decrypt' : 'Download'}</MenuItem>
          <MenuItem icon={<Link2 />} onSelect={() => onOpen({ kind: 'links', file })}>Links and access log</MenuItem>
          <MenuItem icon={<Pencil />} onSelect={() => onOpen({ kind: 'rename', file })}>{file.e2ee ? 'Rename label' : 'Rename'}</MenuItem>
          <MenuSeparator />
          <MenuItem icon={<Trash2 />} danger onSelect={() => onOpen({ kind: 'delete', file })}>Delete</MenuItem>
        </MenuContent>
      </Menu>
    </li>
  );
}

export default function FilesPage() {
  useDocumentTitle('Files');
  const [q, setQ] = useState('');
  const debounced = useDebouncedValue(q.trim(), 300);
  const list = useFiles(debounced);
  const usage = useFileUsage();
  const [modal, setModal] = useState<Modal | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const files = list.data?.pages.flatMap((p) => p.items) ?? [];

  async function download(file: FileItem, keyOverride?: string) {
    if (!file.e2ee) {
      try {
        const { url } = await filesApi.downloadUrl(file.id);
        triggerDownload(url);
      } catch (e) {
        toast.error(errorMessage(e));
      }
      return;
    }
    const key = keyOverride ?? keyStore.get(file.id);
    if (!key) {
      setModal({ kind: 'key', file });
      return;
    }
    setBusyId(file.id);
    try {
      if (canStreamToDisk() && file.sizeBytes >= STREAM_THRESHOLD_BYTES) {
        // Large file: decrypt to disk as it downloads; the save dialog needs one more click.
        const opened = await openContainerStream(responseBytes(await openOwnContentResponse(file.id)), await importKey(key));
        if (keyOverride) keyStore.set(file.id, keyOverride);
        setModal({ kind: 'save', file, opened });
        return;
      }
      if (!canStreamToDisk() && file.sizeBytes > MEMORY_WARNING_BYTES) {
        toast.info(`Decrypting ${formatBytes(file.sizeBytes)} in memory. This browser can't save straight to disk, so large files may fail.`);
      }
      const container = await fetchOwnContent(file.id);
      const { meta, file: plain } = await decryptFile(container, await importKey(key));
      if (keyOverride) keyStore.set(file.id, keyOverride);
      saveBlob(plain, safeFileName(meta.name));
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <PageColumns>
      <PageHeader
        title="Files"
        description="Private to you. Share a file only by creating a link, and turn links off any time."
        actions={<UsageBar />}
      />
      <div className="space-y-6 px-4 pb-10 sm:px-0">
        <UploadPanel maxFileBytes={usage.data?.maxFileBytes} onEncrypted={(file, key) => setModal({ kind: 'share', file, key })} />

        <section aria-label="My files">
          <div className="relative mb-2">
            <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" aria-hidden />
            <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search by name" aria-label="Search files by name" className="pl-9" />
          </div>
          {list.isPending && (
            <div className="space-y-2">
              <Skeleton className="h-14" />
              <Skeleton className="h-14" />
              <Skeleton className="h-14" />
            </div>
          )}
          {list.isError && <ErrorState error={list.error} onRetry={() => list.refetch()} />}
          {list.data && files.length === 0 && (
            <EmptyState
              icon={FolderLock}
              title={debounced ? 'No files match' : 'Your den is empty'}
              description={debounced ? 'Try another part of the name.' : 'No files yet. Upload a document, image, recording or archive. It stays private until you share a link.'}
              mascot={debounced ? 'sit' : 'sleepy'}
            />
          )}
          {files.length > 0 && (
            <ul className="divide-y divide-border/70 rounded-2xl sm:border sm:border-border">
              {files.map((f) => (
                <FileRow key={f.id} file={f} busy={busyId === f.id} onOpen={setModal} onDownload={() => void download(f)} />
              ))}
            </ul>
          )}
          <LoadMore hasMore={!!list.hasNextPage} loading={list.isFetchingNextPage} onLoad={() => list.fetchNextPage()} />
        </section>
      </div>

      {modal?.kind === 'share' && (
        <ShareDialog file={modal.file} initialKey={modal.key} open onOpenChange={(v) => !v && setModal(null)} />
      )}
      {modal?.kind === 'links' && (
        <LinksDialog file={modal.file} open onOpenChange={(v) => !v && setModal(null)} onNewLink={() => setModal({ kind: 'share', file: modal.file })} />
      )}
      {modal?.kind === 'rename' && <RenameDialog file={modal.file} onClose={() => setModal(null)} />}
      {modal?.kind === 'delete' && <DeleteDialog file={modal.file} onClose={() => setModal(null)} />}
      {modal?.kind === 'save' && (
        <Dialog open onOpenChange={(v) => !v && setModal(null)} title="Save decrypted file" size="sm">
          <StreamingSavePrompt
            opened={modal.opened}
            onSaved={(name) => {
              setModal(null);
              toast.success(`Decrypted and saved as “${name}”`);
            }}
          />
        </Dialog>
      )}
      {modal?.kind === 'key' && (
        <KeyPrompt
          file={modal.file}
          onClose={() => setModal(null)}
          onKey={(k) => {
            const f = modal.file;
            setModal(null);
            void download(f, k);
          }}
        />
      )}
    </PageColumns>
  );
}
