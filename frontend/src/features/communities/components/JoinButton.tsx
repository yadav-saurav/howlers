import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, type ButtonSize } from '@/components/ui/Button';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { errorMessage } from '@/services/apiError';
import { useMembership } from '../hooks';

export function JoinButton({
  slug,
  isMember,
  isOwner,
  size = 'sm',
}: {
  slug: string;
  isMember: boolean;
  isOwner?: boolean;
  size?: ButtonSize;
}) {
  const { isAuthed } = useAuth();
  const navigate = useNavigate();
  const m = useMembership(slug);
  const [hover, setHover] = useState(false);
  if (isOwner) return null;
  return (
    <Button
      size={size}
      variant={isMember ? 'outline' : 'primary'}
      loading={m.isPending}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      aria-pressed={isMember}
      onClick={(e) => {
        e.stopPropagation();
        if (!isAuthed) return navigate(`/login?next=/c/${slug}`);
        m.mutate(!isMember, {
          onSuccess: () => toast.success(isMember ? `Left c/${slug}` : `Joined c/${slug}`),
          onError: (err) => toast.error(errorMessage(err)),
        });
      }}
    >
      {isMember ? (hover ? 'Leave' : 'Joined') : 'Join'}
    </Button>
  );
}
