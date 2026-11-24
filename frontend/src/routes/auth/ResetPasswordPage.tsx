import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Field, FormAlert, Input } from '@/components/ui/Field';
import { toast } from '@/components/ui/Toast';
import { authApi } from '@/features/auth/api';
import { AuthCard } from '@/features/auth/components/AuthCard';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { errorMessage } from '@/services/apiError';
import { resetSchema, type ResetValues } from '@/utils/validation';

export default function ResetPasswordPage() {
  useDocumentTitle('Set a new password');
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const navigate = useNavigate();
  const [err, setErr] = useState<string | null>(null);
  const { register, handleSubmit, formState } = useForm<ResetValues>({ resolver: zodResolver(resetSchema) });

  if (!token) {
    return (
      <AuthCard title="Link is incomplete" footer={<Link to="/forgot-password" className="text-accent hover:underline">Request a new link</Link>}>
        <p className="text-center text-sm text-muted">This reset link is missing its token. Open the link from your email again.</p>
      </AuthCard>
    );
  }
  return (
    <AuthCard
      title="Set a new password"
      subtitle="You’ll be signed out of all devices."
      footer={<>Link expired? <Link to="/forgot-password" className="text-accent underline underline-offset-2 hover:no-underline">Request a new one</Link> (links last 1 hour).</>}
    >
      <form
        noValidate
        className="space-y-4"
        onSubmit={handleSubmit(async (v) => {
          setErr(null);
          try {
            await authApi.resetPassword(token, v.newPassword);
            toast.success('Password updated. Log in with your new password.');
            navigate('/login', { replace: true });
          } catch (e) {
            setErr(errorMessage(e));
          }
        })}
      >
        <FormAlert message={err} />
        <Field label="New password" error={formState.errors.newPassword?.message}>{(a) => <Input {...a} type="password" autoComplete="new-password" {...register('newPassword')} />}</Field>
        <Field label="Confirm password" error={formState.errors.confirm?.message}>{(a) => <Input {...a} type="password" autoComplete="new-password" {...register('confirm')} />}</Field>
        <Button type="submit" className="w-full" loading={formState.isSubmitting}>Update password</Button>
      </form>
    </AuthCard>
  );
}
