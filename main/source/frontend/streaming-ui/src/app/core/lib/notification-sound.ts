/**
 * Play a short ascending notification tone via the Web Audio API.
 * No external audio file required — tiny footprint, works offline.
 *
 * The beep is a 200ms sine sweep (800→1000 Hz) at low volume.
 * Browsers may block audio before the first user gesture — the
 * try/catch swallows the autoplay-policy error silently so the
 * toast still renders.
 */
export function playNotificationSound(): void {
  try {
    const ctx = new AudioContext();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    osc.type = 'sine';
    osc.frequency.setValueAtTime(800, ctx.currentTime);
    osc.frequency.linearRampToValueAtTime(1000, ctx.currentTime + 0.1);

    gain.gain.setValueAtTime(0.3, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.2);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start(ctx.currentTime);
    osc.stop(ctx.currentTime + 0.2);
  } catch {
    // Autoplay policy blocked the AudioContext — silent fallback.
  }
}
