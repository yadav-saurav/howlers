import { Link, useLocation, useNavigate } from 'react-router-dom';
import { AuthCard } from '@/features/auth/components/AuthCard';
import { RegisterForm } from '@/features/auth/components/RegisterForm';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';

export default function RegisterPage() {
  useDocumentTitle('Join');
  const navigate = useNavigate();
  const { search } = useLocation();
  return (
    <AuthCard
      title="Join Howlers"
      subtitle="Communities that turn conversation into knowledge."
      footer={<>Already have an account? <Link to={`/login${search}`} className="font-medium text-accent hover:underline">Log in</Link></>}
    >
      <RegisterForm onSuccess={() => navigate('/onboarding', { replace: true })} />
    </AuthCard>
  );
}
