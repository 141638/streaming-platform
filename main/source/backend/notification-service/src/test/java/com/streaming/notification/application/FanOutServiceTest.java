package com.streaming.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.common.messaging.StreamEvent;
import com.streaming.notification.domain.FanOutJob;
import com.streaming.notification.infrastructure.persistence.ReactiveFanOutJobRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("FanOutService")
@ExtendWith(MockitoExtension.class)
class FanOutServiceTest {

    private static final String EVENT_ID = "evt-123";
    private static final String BROADCASTER_SUB = "broadcaster-1";

    @Mock
    private ReactiveFanOutJobRepository fanOutJobRepository;

    private FanOutService fanOutService;

    @BeforeEach
    void setUp() {
        fanOutService = new FanOutService(fanOutJobRepository);
    }

    private static StreamEvent streamEvent() {
        return new StreamEvent(
                "STREAM_STARTED",
                "stream-1",
                EVENT_ID,
                OffsetDateTime.now(ZoneOffset.UTC),
                BROADCASTER_SUB,
                "Streamer One",
                null,
                null);
    }

    @Test
    @DisplayName("enqueue creates a PENDING fan-out job when none exists for the event")
    void enqueue_newEvent_createsJob() {
        when(fanOutJobRepository.existsByEventIdAndJobType(EVENT_ID, "STREAM_STARTED"))
                .thenReturn(Mono.just(false));
        ArgumentCaptor<FanOutJob> jobCaptor = ArgumentCaptor.forClass(FanOutJob.class);
        when(fanOutJobRepository.save(jobCaptor.capture()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(fanOutService.enqueue(streamEvent()))
                .verifyComplete();

        verify(fanOutJobRepository).save(any(FanOutJob.class));
        FanOutJob saved = jobCaptor.getValue();
        assertThat(saved).isNotNull();
        assertThat(saved.getEventId()).isEqualTo(EVENT_ID);
        assertThat(saved.getJobType()).isEqualTo("STREAM_STARTED");
        assertThat(saved.getBroadcasterSubject()).isEqualTo(BROADCASTER_SUB);
        assertThat(saved.getTargetType()).isEqualTo("CHANNEL");
        assertThat(saved.getTargetId()).isEqualTo(BROADCASTER_SUB);
        assertThat(saved.getState()).isEqualTo(FanOutJob.STATE_PENDING);
        assertThat(saved.getRetryCount()).isZero();
        assertThat(saved.getProcessedSubscribers()).isZero();
        assertThat(saved.isNew()).isTrue();
    }

    @Test
    @DisplayName("enqueue skips when a job already exists for the (eventId, jobType) pair")
    void enqueue_duplicateEvent_skipsJob() {
        when(fanOutJobRepository.existsByEventIdAndJobType(EVENT_ID, "STREAM_STARTED"))
                .thenReturn(Mono.just(true));

        StepVerifier.create(fanOutService.enqueue(streamEvent()))
                .verifyComplete();

        verify(fanOutJobRepository, never()).save(any(FanOutJob.class));
    }

    @Test
    @DisplayName("enqueue propagates a save failure")
    void enqueue_saveError_propagates() {
        when(fanOutJobRepository.existsByEventIdAndJobType(EVENT_ID, "STREAM_STARTED"))
                .thenReturn(Mono.just(false));
        when(fanOutJobRepository.save(any(FanOutJob.class)))
                .thenReturn(Mono.error(new RuntimeException("db down")));

        StepVerifier.create(fanOutService.enqueue(streamEvent()))
                .expectErrorMatches(err -> err instanceof RuntimeException
                        && "db down".equals(err.getMessage()))
                .verify();
    }
}
