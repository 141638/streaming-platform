import { formatExpiresIn } from './time';

describe('formatExpiresIn', () => {
  const now = Date.parse('2026-07-11T00:00:00Z');

  it('returns "expired" once the instant has passed', () => {
    expect(formatExpiresIn('2026-07-10T23:59:59Z', now)).toBe('expired');
  });

  it('returns "expired" for an unparseable timestamp', () => {
    expect(formatExpiresIn('not-a-date', now)).toBe('expired');
  });

  it('formats a sub-hour remainder in whole minutes', () => {
    expect(formatExpiresIn('2026-07-11T00:42:00Z', now)).toBe('expires in 42m');
  });

  it('formats a multi-hour remainder in whole hours', () => {
    expect(formatExpiresIn('2026-07-11T03:00:00Z', now)).toBe('expires in 3h');
  });

  it('formats a multi-day remainder in whole days', () => {
    expect(formatExpiresIn('2026-07-13T00:00:00Z', now)).toBe('expires in 2d');
  });

  it('never renders "0m" while the ban is still active', () => {
    expect(formatExpiresIn('2026-07-11T00:00:30Z', now)).toBe('expires in 1m');
  });
});
