import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';
import { TagModule } from 'primeng/tag';

type TagSeverity = 'success' | 'info' | 'warn' | 'danger' | 'secondary';

interface StatusPresentation {
  readonly label: string;
  readonly severity: TagSeverity;
  readonly icon: string;
}

const STATUS_PRESENTATION: Readonly<Record<string, StatusPresentation>> = {
  DRAFT: { label: 'Draft', severity: 'secondary', icon: 'pi pi-pencil' },
  SCHEDULED: { label: 'Scheduled', severity: 'info', icon: 'pi pi-calendar' },
  LIVE: { label: 'Live', severity: 'danger', icon: 'pi pi-circle-fill' },
  ENDED: { label: 'Ended', severity: 'secondary', icon: 'pi pi-stop-circle' },
  CANCELLED: { label: 'Cancelled', severity: 'warn', icon: 'pi pi-times-circle' },
};

const UNKNOWN_PRESENTATION: StatusPresentation = {
  label: 'Unknown',
  severity: 'secondary',
  icon: 'pi pi-question-circle',
};

@Component({
  selector: 'app-stream-status-badge',
  standalone: true,
  imports: [TagModule],
  templateUrl: './stream-status-badge.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StreamStatusBadgeComponent {
  public readonly status = input.required<string>();

  protected readonly presentation = computed<StatusPresentation>(
    () =>
      STATUS_PRESENTATION[this.status().toUpperCase()] ?? UNKNOWN_PRESENTATION,
  );
}
