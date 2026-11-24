import { brand } from '@/design-system/brand';
import { StaticPage } from './StaticPage';

export default function TermsPage() {
  return (
    <StaticPage title="Terms of Service" updated="November 2026">
      <p>
        These terms are a working draft for a product in development and are not legal advice. They will be reviewed
        before any public launch.
      </p>
      <h2>Your account</h2>
      <p>You are responsible for activity on your account and for keeping your password secure. You must be old enough to consent to these terms where you live.</p>
      <h2>Your content</h2>
      <p>
        You keep ownership of what you post. By posting, you grant {brand.name} a licence to host, display and distribute it
        on the platform. Deleting content removes it from public view; some records may be retained for moderation and legal obligations.
      </p>
      <h2>Community rules</h2>
      <p>Communities set their own rules on top of platform rules. Moderators may remove content or restrict participation within their communities. Platform administrators may act across the whole platform.</p>
      <h2>Prohibited conduct</h2>
      <ul>
        <li>Harassment, threats or hate directed at people.</li>
        <li>Spam, vote manipulation or coordinated inauthentic behaviour.</li>
        <li>Sharing others’ private information without consent.</li>
        <li>Content that is illegal where it is posted or viewed.</li>
      </ul>
      <h2>Changes</h2>
      <p>We will announce material changes to these terms before they take effect.</p>
    </StaticPage>
  );
}
