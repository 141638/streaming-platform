/**
 * Formats a number into a compact human-readable string.
 * 1,234,567 → "1.23M"
 * 12,345 → "12.3K"
 * 123 → "123"
 */
export function formatCount(value: number | null | undefined): string {
  if (value == null || value === 0) return '0';
  if (value < 1_000) return String(value);
  if (value < 1_000_000) {
    return (value / 1_000).toFixed(1).replace(/\.0$/, '') + 'K';
  }
  return (value / 1_000_000).toFixed(2).replace(/\.00$/, '').replace(/\.0$/, '') + 'M';
}
