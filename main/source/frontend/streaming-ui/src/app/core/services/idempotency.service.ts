import { HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';

/**
 * Generates and manages idempotency keys for state-changing HTTP requests.
 *
 * <p>Each user action (click, submit, etc.) produces one key. The key travels
 * as an {@code Idempotency-Key} header through the gateway, which caches the
 * successful response. Retries of the same action reuse the same key — the
 * gateway returns the cached response instead of calling the downstream
 * service again.
 *
 * <p><strong>Key lifetime:</strong> a key represents one attempt at an action.
 * If the action fails with a 4xx error, the key is consumed and a retry should
 * generate a new key (it's a new attempt). If the action fails with a
 * retryable error (network, 5xx, token expiry), the caller retries with the
 * <em>same</em> key.
 */
@Injectable({ providedIn: 'root' })
export class IdempotencyService {
  /** Generate a fresh key per user action. */
  public newKey(): string {
    return crypto.randomUUID();
  }

  /**
   * Return an options object suitable for spreading into
   * {@code HttpClient} methods. When {@code key} is undefined
   * the result is empty — the header is omitted from the request.
   *
   * <p>Usage:
   * <pre>
   *   this.http.post(url, body, idempotencyService.options(key))
   * </pre>
   */
  public static options(key?: string): { headers?: HttpHeaders } {
    if (!key) return {};
    return { headers: new HttpHeaders({ 'Idempotency-Key': key }) };
  }
}
