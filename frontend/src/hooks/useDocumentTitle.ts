import { useEffect } from 'react';
import { brand } from '@/design-system/brand';

export function useDocumentTitle(title?: string | null) {
  useEffect(() => {
    document.title = title ? `${title} · ${brand.name}` : brand.name;
  }, [title]);
}
