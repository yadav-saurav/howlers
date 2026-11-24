import { Bookmark } from 'lucide-react';
import { Link } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { PostList } from '@/features/posts/components/PostList';
import { useSavedPosts } from '@/features/posts/hooks';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';

export default function SavedPage() {
  useDocumentTitle('Saved');
  const saved = useSavedPosts();
  return (
    <PageColumns>
      <PageHeader title="Saved" description="Posts you saved for later. Only you can see this list." />
      <PostList
        query={saved}
        empty={{
          icon: Bookmark,
          title: 'Nothing saved yet',
          description: 'Use Save on any post to keep it here.',
          action: <Link to="/home" className={buttonClasses('primary', 'sm')}>Browse your feed</Link>,
        }}
      />
    </PageColumns>
  );
}
