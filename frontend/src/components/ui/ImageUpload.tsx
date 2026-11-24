import { ImageUp } from 'lucide-react';
import { useId, useRef } from 'react';
import { toast } from './Toast';
import { Button } from './Button';

const ACCEPT = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];

/** File picker for images with client-side type/size checks. Server validation remains authoritative. */
export function ImageUpload({
  label,
  onFile,
  loading,
  maxMb = 5,
  variant = 'outline',
}: {
  label: string;
  onFile: (f: File) => void;
  loading?: boolean;
  maxMb?: number;
  variant?: 'outline' | 'secondary' | 'ghost';
}) {
  const ref = useRef<HTMLInputElement>(null);
  const id = useId();
  return (
    <>
      <input
        ref={ref}
        id={id}
        type="file"
        accept={ACCEPT.join(',')}
        className="sr-only"
        tabIndex={-1}
        // The visible button below is the control; this input is only its file picker.
        aria-hidden="true"
        aria-label={label}
        onChange={(e) => {
          const f = e.target.files?.[0];
          e.target.value = '';
          if (!f) return;
          if (!ACCEPT.includes(f.type)) return toast.error('Use a PNG, JPEG, WebP or GIF image');
          if (f.size > maxMb * 1024 * 1024) return toast.error(`Images must be ${maxMb} MB or smaller`);
          onFile(f);
        }}
      />
      <Button variant={variant} size="sm" loading={loading} onClick={() => ref.current?.click()}>
        <ImageUp size={15} /> {label}
      </Button>
    </>
  );
}
