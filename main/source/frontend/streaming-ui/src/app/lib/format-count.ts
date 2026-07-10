/**
 * Format a number with K/M/B suffix for channel page counts.
 * Examples: 1,234 → "1.2K", 1,500,000 → "1.5M", 0 → "0".
 */
export function formatCount(value: number): string {
  if (value < 1_000) {
    return value.toString();
  }
  if (value < 1_000_000) {
    const k = value / 1_000;
    return k >= 100 ? Math.round(k) + 'K' : k.toFixed(1).replace(/\.0$/, '') + 'K';
  }
  if (value < 1_000_000_000) {
    const m = value / 1_000_000;
    return m >= 100 ? Math.round(m) + 'M' : m.toFixed(1).replace(/\.0$/, '') + 'M';
  }
  const b = value / 1_000_000_000;
  return b >= 100 ? Math.round(b) + 'B' : b.toFixed(1).replace(/\.0$/, '') + 'B';
}
