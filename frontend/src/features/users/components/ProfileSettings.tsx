import { useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/Button';
import { Avatar, Card, CardHeader } from '@/components/ui/Display';
import { Field, Input, Textarea } from '@/components/ui/Field';
import { ImageUpload } from '@/components/ui/ImageUpload';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { Me } from '@/types/api';
import { useUpdateMe, useUploadAvatar, useUploadBanner } from '../hooks';

/** A local preview for a picked file; the object URL is released when the file changes or the page closes. */
function usePreview(file: File | null) {
  const url = useMemo(() => (file ? URL.createObjectURL(file) : null), [file]);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);
  return url;
}

/**
 * Picking a new avatar or banner shows a preview straight away; "Save profile" uploads it together with the
 * name and bio, so everything is saved and applied in one step.
 */
export function ProfileSettings({ me }: { me: Me }) {
  const qc = useQueryClient();
  const update = useUpdateMe();
  const avatar = useUploadAvatar();
  const banner = useUploadBanner();
  const [displayName, setDisplayName] = useState(me.displayName ?? '');
  const [bio, setBio] = useState(me.bio ?? '');
  const [avatarFile, setAvatarFile] = useState<File | null>(null);
  const [bannerFile, setBannerFile] = useState<File | null>(null);
  const [saving, setSaving] = useState(false);
  const avatarPreview = usePreview(avatarFile);
  const bannerPreview = usePreview(bannerFile);
  const err = displayName.length > 40 ? 'At most 40 characters' : undefined;
  const bioErr = bio.length > 300 ? 'At most 300 characters' : undefined;
  const textChanged = displayName.trim() !== (me.displayName ?? '') || bio.trim() !== (me.bio ?? '');
  const dirty = textChanged || !!avatarFile || !!bannerFile;
  const bannerUrl = bannerPreview ?? me.bannerUrl;

  const save = async () => {
    setSaving(true);
    try {
      if (avatarFile) await avatar.mutateAsync(avatarFile);
      setAvatarFile(null);
      if (bannerFile) await banner.mutateAsync(bannerFile);
      setBannerFile(null);
      if (textChanged) await update.mutateAsync({ displayName: displayName.trim(), bio: bio.trim() });
      // Profile pages, posts and chats show the picture too: refresh whatever is on screen.
      await qc.invalidateQueries();
      toast.success('Profile saved');
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader title="Images" />
        <div className="flex flex-wrap items-center gap-6 p-4">
          <div className="flex items-center gap-3">
            <Avatar src={avatarPreview ?? me.avatarUrl} name={me.username} size="lg" />
            <div className="flex flex-col items-start gap-1">
              <ImageUpload label="Change avatar" onFile={setAvatarFile} />
              {avatarFile && (
                <button type="button" className="text-xs text-muted underline" onClick={() => setAvatarFile(null)}>
                  Undo
                </button>
              )}
            </div>
          </div>
          <div className="flex flex-1 items-center gap-3">
            <div
              role="img"
              aria-label={bannerPreview ? 'New banner (not saved yet)' : 'Current banner'}
              className="h-16 min-w-[160px] flex-1 rounded-md border border-border bg-surface-2"
              style={bannerUrl ? { backgroundImage: `url(${bannerUrl})`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined}
            />
            <div className="flex flex-col items-start gap-1">
              <ImageUpload label="Change banner" maxMb={10} onFile={setBannerFile} />
              {bannerFile && (
                <button type="button" className="text-xs text-muted underline" onClick={() => setBannerFile(null)}>
                  Undo
                </button>
              )}
            </div>
          </div>
        </div>
        {(avatarFile || bannerFile) && (
          <p className="px-4 pb-4 text-xs text-accent" role="status">
            New picture ready. Tap <strong>Save profile</strong> to apply it.
          </p>
        )}
      </Card>
      <Card>
        <CardHeader title="Public profile" />
        <div className="space-y-4 p-4">
          <Field label="Display name" optional error={err} hint="Shown next to your username.">
            {(a) => <Input {...a} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />}
          </Field>
          <Field label="Bio" optional error={bioErr} hint={`${bio.length}/300`}>
            {(a) => <Textarea {...a} rows={4} value={bio} onChange={(e) => setBio(e.target.value)} placeholder="What do you work on or care about?" />}
          </Field>
          <div className="flex justify-end">
            <Button disabled={!!err || !!bioErr || !dirty} loading={saving} onClick={save}>
              Save profile
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}
