import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http } from '@/services/apiClient';

/** Mirrors backend app.howlers.ai.AiDtos. */
export interface AiSource {
  n: number;
  kind: 'POST' | 'COMMENT';
  postId: number;
  commentId: number | null;
  title: string;
  communitySlug: string;
  author: string | null;
  score: number;
  createdAt: string;
  excerpt: string;
  link: string | null;
  cited: boolean;
}

export interface AiMessage {
  id: number;
  role: 'USER' | 'ASSISTANT';
  content: string;
  engine: string | null;
  intent: string | null;
  sources: AiSource[];
  followUps: string[];
  notice: string | null;
  feedback: -1 | 0 | 1;
  createdAt: string;
}

export interface AiConversationSummary {
  id: number;
  title: string;
  communitySlug: string | null;
  postId: number | null;
  updatedAt: string;
}

export interface AiConversation extends Omit<AiConversationSummary, 'updatedAt'> {
  createdAt: string;
  messages: AiMessage[];
}

export interface AiStatus {
  provider: string;
  model: string;
  llmConfigured: boolean;
  note: string;
}

export interface AskBody {
  conversationId?: number;
  question: string;
  communitySlug?: string;
  postId?: number;
  sinceDays?: number;
}

export const aiApi = {
  status: () => http.get<AiStatus>('/ai/status'),
  list: () => http.get<AiConversationSummary[]>('/me/ai/conversations'),
  get: (id: number) => http.get<AiConversation>(`/me/ai/conversations/${id}`),
  remove: (id: number) => http.del<void>(`/me/ai/conversations/${id}`),
  ask: (body: AskBody) =>
    http.post<{ conversation: AiConversationSummary; question: AiMessage; answer: AiMessage }>('/me/ai/ask', body),
  feedback: (messageId: number, value: -1 | 0 | 1) => http.post<void>(`/me/ai/messages/${messageId}/feedback`, { value }),
};

export const aiKeys = {
  status: ['ai', 'status'] as const,
  list: ['ai', 'conversations'] as const,
  conversation: (id: number) => ['ai', 'conversation', id] as const,
};

export function useAiStatus() {
  return useQuery({ queryKey: aiKeys.status, queryFn: aiApi.status, staleTime: 5 * 60_000 });
}

export function useAiConversations() {
  return useQuery({ queryKey: aiKeys.list, queryFn: aiApi.list });
}

export function useAiConversation(id: number | null) {
  return useQuery({ queryKey: aiKeys.conversation(id ?? 0), queryFn: () => aiApi.get(id!), enabled: id != null });
}

export function useAsk() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: aiApi.ask,
    onSuccess: (res) => {
      qc.setQueryData<AiConversation>(aiKeys.conversation(res.conversation.id), (old) =>
        old
          ? { ...old, messages: [...old.messages, res.question, res.answer] }
          : { ...res.conversation, createdAt: res.question.createdAt, messages: [res.question, res.answer] },
      );
      qc.invalidateQueries({ queryKey: aiKeys.list });
    },
  });
}

export function useDeleteConversation() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: aiApi.remove, onSuccess: () => qc.invalidateQueries({ queryKey: aiKeys.list }) });
}

export function useAiFeedback(conversationId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, value }: { id: number; value: -1 | 0 | 1 }) => aiApi.feedback(id, value),
    onMutate: ({ id, value }) =>
      qc.setQueryData<AiConversation>(aiKeys.conversation(conversationId), (old) =>
        old ? { ...old, messages: old.messages.map((m) => (m.id === id ? { ...m, feedback: value } : m)) } : old,
      ),
  });
}
