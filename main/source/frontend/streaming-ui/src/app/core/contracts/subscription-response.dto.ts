/** Backend wire type returned by subscription endpoints. */
export interface SubscriptionResponseDto {
  readonly id: string;
  readonly subscriberSubject: string;
  readonly targetType: string;
  readonly targetId: string;
  readonly active: boolean;
  readonly createdAt: string;
}
