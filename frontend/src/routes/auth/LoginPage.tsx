import { Link, useLocation, useNavigate } from 'react-router-dom';
import { safeNext } from '@/app/guards';
import { AuthCard } from '@/features/auth/components/AuthCard';
import { LoginForm } from '@/features/auth/components/LoginForm';
import { PasskeyLoginButton } from '@/features/accountSecurity/components/PasskeyLoginButton';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import type { Me } from '@/types/api';

export default function LoginPage() {
  useDocumentTitle('Log in');
  const navigate = useNavigate();
  const { search } = useLocation();
  const done = (me: Me) => navigate(me.onboardingCompleted ? safeNext(search) : '/onboarding', { replace: true });
  return (
    <AuthCard
      title="Welcome back"
      subtitle="Log in to vote, comment and follow your communities."
      footer={<>New here? <Link to={`/register${search}`} className="font-medium text-accent hover:underline">Create an account</Link></>}
    >
      <LoginForm onSuccess={done} />
      <div className="mt-4">
        <PasskeyLoginButton onSuccess={done} />
      </div>
    </AuthCard>
  );
}
