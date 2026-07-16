/** Backend wire type for {@code PUT /v1/subscriptions} request body. */
export interface SubscriptionRequestDto {
  readonly targetType: string;
  readonly targetId: string;
}
