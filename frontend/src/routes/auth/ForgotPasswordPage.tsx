import { zodResolver } from '@hookform/resolvers/zod';
import { MailCheck } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { z } from 'zod';
import { Button } from '@/components/ui/Button';
import { Field, FormAlert, Input } from '@/components/ui/Field';
import { authApi } from '@/features/auth/api';
import { AuthCard } from '@/features/auth/components/AuthCard';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { errorMessage } from '@/services/apiError';
import { emailSchema } from '@/utils/validation';

const schema = z.object({ email: emailSchema });

export default function ForgotPasswordPage() {
  useDocumentTitle('Reset password');
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const { register, handleSubmit, formState } = useForm<{ email: string }>({ resolver: zodResolver(schema) });

  if (sentTo) {
    return (
      <AuthCard title="Check your inbox" footer={<Link to="/login" className="text-accent hover:underline">Back to log in</Link>}>
        <div className="flex flex-col items-center gap-3 text-center text-sm text-muted">
          <MailCheck size={28} className="text-accent" />
          <p>If an account exists for <span className="font-medium text-text">{sentTo}</span>, a reset link is on its way. It expires in 30 minutes.</p>
        </div>
      </AuthCard>
    );
  }
  return (
    <AuthCard title="Forgot your password?" subtitle="We’ll email you a link to set a new one." footer={<Link to="/login" className="text-accent hover:underline">Back to log in</Link>}>
      <form
        noValidate
        className="space-y-4"
        onSubmit={handleSubmit(async ({ email }) => {
          setErr(null);
          try {
            await authApi.forgotPassword(email);
            setSentTo(email);
          } catch (e) {
            setErr(errorMessage(e));
          }
        })}
      >
        <FormAlert message={err} />
        <Field label="Email" error={formState.errors.email?.message}>{(a) => <Input {...a} type="email" autoComplete="email" {...register('email')} />}</Field>
        <Button type="submit" className="w-full" loading={formState.isSubmitting}>Send reset link</Button>
      </form>
    </AuthCard>
  );
}
