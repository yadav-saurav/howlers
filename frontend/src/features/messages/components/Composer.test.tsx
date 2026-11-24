import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Composer, type ReplyDraft } from './Composer';

const reply: ReplyDraft = {
  preview: { id: 4, senderId: 2, excerpt: 'see you at eight', deleted: false, mediaKind: null },
  senderName: 'Bo',
};

function setup(extra: Partial<Parameters<typeof Composer>[0]> = {}) {
  const props = {
    conversationId: 1,
    disabledReason: null,
    onSend: vi.fn(),
    onSendVoice: vi.fn(),
    reply: null,
    onCancelReply: vi.fn(),
    ...extra,
  };
  render(<Composer {...props} />);
  return props;
}

describe('Composer', () => {
  it('shows the mic until there is text, then Send; Enter sends', async () => {
    const user = userEvent.setup();
    const props = setup();
    expect(screen.getByRole('button', { name: 'Attach photos' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record voice message' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send' })).not.toBeInTheDocument();

    const box = screen.getByRole('textbox', { name: 'Message' });
    await user.type(box, 'hello');
    expect(screen.getByRole('button', { name: 'Send' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record voice message' })).not.toBeInTheDocument();

    await user.type(box, '{Shift>}{Enter}{/Shift}there{Enter}');
    expect(props.onSend).toHaveBeenCalledWith('hello\nthere', []);
    expect(box).toHaveValue('');
  });

  it('shows the quoted reply and cancels it with X or Escape', async () => {
    const user = userEvent.setup();
    const props = setup({ reply });
    expect(screen.getByText('Replying to Bo')).toBeInTheDocument();
    expect(screen.getByText('see you at eight')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Message' })).toHaveFocus());
    await user.click(screen.getByRole('button', { name: 'Cancel reply' }));
    expect(props.onCancelReply).toHaveBeenCalledTimes(1);
    screen.getByRole('textbox', { name: 'Message' }).focus();
    await user.keyboard('{Escape}');
    expect(props.onCancelReply).toHaveBeenCalledTimes(2);
  });

  it('explains a blocked microphone', async () => {
    const user = userEvent.setup();
    const getUserMedia = vi.fn().mockRejectedValue(Object.assign(new Error('denied'), { name: 'NotAllowedError' }));
    Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia }, configurable: true });
    vi.stubGlobal('MediaRecorder', Object.assign(function MediaRecorder() {}, { isTypeSupported: () => true }));
    setup();
    await user.click(screen.getByRole('button', { name: 'Record voice message' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Microphone access is blocked/);
    vi.unstubAllGlobals();
  });

  it('shows the reason instead of the box when messaging is not allowed', () => {
    setup({ disabledReason: 'mira_k only accepts messages from people they follow.' });
    expect(screen.getByRole('note')).toHaveTextContent('only accepts messages');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });
});
