import { describe, expect, it } from 'vitest';
import { downloadsText, expiryFromPreset, formatBytes, passphraseProblem, scanSummary, shareUrl, typeLabel, usageFraction } from './helpers';

describe('files helpers', () => {
  it('never calls a structural check an antivirus scan', () => {
    expect(scanSummary('CLAMAV').text).toContain('Scanned by ClamAV');
    expect(scanSummary('STRUCTURAL').text).toContain('Not an antivirus scan');
    expect(scanSummary(undefined).badge).toBe('Checked');
  });

  it('formats byte sizes', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1023)).toBe('1023 B');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(1024 * 1024)).toBe('1 MB');
    expect(formatBytes(1024 ** 3 * 1.25)).toBe('1.3 GB');
    expect(formatBytes(-1)).toBe('—');
  });

  it('clamps usage', () => {
    expect(usageFraction(512, 1024)).toBe(0.5);
    expect(usageFraction(2048, 1024)).toBe(1);
    expect(usageFraction(1, 0)).toBe(1);
  });

  it('turns expiry presets into timestamps', () => {
    const now = Date.UTC(2026, 0, 1);
    expect(expiryFromPreset('never', now)).toBeNull();
    expect(expiryFromPreset('1d', now)).toBe('2026-01-02T00:00:00.000Z');
  });

  it('puts the E2EE key only in the URL fragment', () => {
    expect(shareUrl('https://x.test', 'abc')).toBe('https://x.test/s/abc');
    expect(shareUrl('https://x.test', 'abc', 'KEY')).toBe('https://x.test/s/abc#k=KEY');
  });

  it('describes downloads and types', () => {
    expect(downloadsText({ downloadCount: 1, maxDownloads: null })).toBe('1 download');
    expect(downloadsText({ downloadCount: 2, maxDownloads: 5 })).toBe('2 of 5 downloads');
    expect(typeLabel('application/pdf')).toBe('PDF');
    expect(typeLabel('image/png', 'a.png')).toBe('PNG image');
    expect(typeLabel('application/zip', 'a.zip')).toBe('ZIP archive');
  });

  it('checks passphrases', () => {
    expect(passphraseProblem('short', 'short')).toMatch(/10 characters/);
    expect(passphraseProblem('long enough words', 'long enough wordz')).toMatch(/match/);
    expect(passphraseProblem('long enough words', 'long enough words')).toBeNull();
  });
});
