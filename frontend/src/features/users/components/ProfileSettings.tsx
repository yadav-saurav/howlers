import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Avatar, Card, CardHeader } from '@/components/ui/Display';
import { Field, Input, Textarea } from '@/components/ui/Field';
import { ImageUpload } from '@/components/ui/ImageUpload';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { Me } from '@/types/api';
import { useUpdateMe, useUploadAvatar, useUploadBanner } from '../hooks';

export function ProfileSettings({ me }: { me: Me }) {
  const update = useUpdateMe();
  const avatar = useUploadAvatar();
  const banner = useUploadBanner();
  const [displayName, setDisplayName] = useState(me.displayName ?? '');
  const [bio, setBio] = useState(me.bio ?? '');
  const err = displayName.length > 40 ? 'At most 40 characters' : undefined;
  const bioErr = bio.length > 300 ? 'At most 300 characters' : undefined;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader title="Images" />
        <div className="flex flex-wrap items-center gap-6 p-4">
          <div className="flex items-center gap-3">
            <Avatar src={me.avatarUrl} name={me.username} size="lg" />
            <ImageUpload label="Change avatar" loading={avatar.isPending} onFile={(f) => avatar.mutate(f, { onSuccess: () => toast.success('Avatar updated'), onError: (e) => toast.error(errorMessage(e)) })} />
          </div>
          <div className="flex flex-1 items-center gap-3">
            <div
              role="img"
              aria-label="Current banner"
              className="h-16 min-w-[160px] flex-1 rounded-md border border-border bg-surface-2"
              style={me.bannerUrl ? { backgroundImage: `url(${me.bannerUrl})`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined}
            />
            <ImageUpload label="Change banner" maxMb={10} loading={banner.isPending} onFile={(f) => banner.mutate(f, { onSuccess: () => toast.success('Banner updated'), onError: (e) => toast.error(errorMessage(e)) })} />
          </div>
        </div>
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
            <Button
              disabled={!!err || !!bioErr}
              loading={update.isPending}
              onClick={() => update.mutate({ displayName: displayName.trim(), bio: bio.trim() }, { onSuccess: () => toast.success('Profile saved'), onError: (e) => toast.error(errorMessage(e)) })}
            >
              Save profile
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}
