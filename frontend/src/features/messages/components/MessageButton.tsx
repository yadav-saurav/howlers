import { MessagesSquare } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import { useOpenConversation } from '../api';

/** Opens (or creates) the conversation with `username` and navigates to it. */
export function MessageButton({ username }: { username: string }) {
  const open = useOpenConversation();
  const navigate = useNavigate();
  return (
    <Button
      size="sm"
      variant="outline"
      loading={open.isPending}
      onClick={() => open.mutate(username, { onSuccess: (c) => navigate(`/messages/${c.id}`), onError: (e) => toast.error(errorMessage(e)) })}
    >
      <MessagesSquare size={15} /> Message
    </Button>
  );
}
