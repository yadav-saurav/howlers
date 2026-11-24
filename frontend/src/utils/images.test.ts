import { describe, expect, it } from 'vitest';
import { responsiveImage, sizedUrl } from './images';

describe('sizedUrl', () => {
  it('asks our media endpoints for a variant', () => {
    expect(sizedUrl('/api/v1/files/avatars/abc.png', 320)).toBe('/api/v1/files/avatars/abc.png?w=320');
    expect(sizedUrl('/api/v1/dm-media/grant.sig', 960)).toBe('/api/v1/dm-media/grant.sig?w=960');
  });

  it('leaves other URLs and existing queries alone', () => {
    expect(sizedUrl('https://example.com/a.png', 320)).toBe('https://example.com/a.png');
    expect(sizedUrl('blob:http://localhost/123', 320)).toBe('blob:http://localhost/123');
    expect(sizedUrl('/api/v1/files/a.png?w=960', 320)).toBe('/api/v1/files/a.png?w=960');
    expect(sizedUrl(null, 320)).toBeUndefined();
  });
});

describe('responsiveImage', () => {
  it('lists variants and the original with width descriptors', () => {
    const r = responsiveImage({
      url: '/api/v1/files/media/x.jpg',
      width: 2400,
      thumbnails: { w320: '/api/v1/files/media/x_w320.jpg', w960: '/api/v1/files/media/x_w960.jpg' },
    });
    expect(r.src).toBe('/api/v1/files/media/x_w960.jpg');
    expect(r.srcSet).toBe('/api/v1/files/media/x_w320.jpg 320w, /api/v1/files/media/x_w960.jpg 960w, /api/v1/files/media/x.jpg 2400w');
  });

  it('includes the original when only the small variant exists', () => {
    const r = responsiveImage({ url: '/o.png', width: 600, thumbnails: { w320: '/o_w320.png', w960: null } });
    expect(r.srcSet).toBe('/o_w320.png 320w, /o.png 600w');
    expect(r.src).toBe('/o_w320.png');
  });

  it('falls back to the original for older uploads', () => {
    expect(responsiveImage({ url: '/old.png', width: 800, thumbnails: null })).toEqual({ src: '/old.png' });
    expect(responsiveImage({ url: '/old.png', width: null })).toEqual({ src: '/old.png' });
  });
});
