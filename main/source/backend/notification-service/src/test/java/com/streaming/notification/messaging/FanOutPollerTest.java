package com.streaming.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.common.messaging.StreamEvent;
import com.streaming.notification.application.NotificationDispatcher;
import com.streaming.notification.application.NotificationService;
import com.streaming.notification.application.SubscriptionService;
import com.streaming.notification.domain.FanOutJob;
import com.streaming.notification.domain.Notification;
import com.streaming.notification.domain.NotificationCategory;
import com.streaming.notification.domain.Subscription;
import com.streaming.notification.infrastructure.persistence.ReactiveFanOutJobRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("FanOutPoller")
@ExtendWith(MockitoExtension.class)
class FanOutPollerTest {

    private static final String BROADCASTER_SUB = "broadcaster-1";
    private static final String INSTANCE_ID = "test-instance";

    @Mock
    private ReactiveFanOutJobRepository jobRepository;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private NotificationDispatcher dispatcher;

    private FanOutPoller poller;

    @BeforeEach
    void setUp() {
        poller = new FanOutPoller(jobRepository, subscriptionService, notificationService, dispatcher);
        // @Value fields are populated by Spring in production — set them explicitly in unit tests.
        ReflectionTestUtils.setField(poller, "batchSize", 10);
        ReflectionTestUtils.setField(poller, "maxConcurrency", 4);
        ReflectionTestUtils.setField(poller, "maxRetries", 3);
        ReflectionTestUtils.setField(poller, "processingTimeoutSeconds", 60);
        ReflectionTestUtils.setField(poller, "instanceId", INSTANCE_ID);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Invoke a private poller method via reflection. The internal methods are
     * package-agnostic reactive chains; testing them directly avoids blocking
     * on the {@code @Scheduled} entry point ({@code poll()}).
     */
    @SuppressWarnings("unchecked")
    private static Mono<Void> invokePollerMethod(FanOutPoller poller, String methodName, Object... args) {
        return (Mono<Void>) ReflectionTestUtils.invokeMethod(poller, methodName, args);
    }

    private static FanOutJob job() {
        return FanOutJob.create(
                "evt-1", "STREAM_STARTED", BROADCASTER_SUB, "CHANNEL", BROADCASTER_SUB,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static Subscription subscription(String subscriberSubject) {
        return Subscription.create(subscriberSubject, "CHANNEL", BROADCASTER_SUB,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static List<Subscription> subscriptions(int count) {
        List<Subscription> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(subscription("sub-" + i));
        }
        return result;
    }

    private static Notification notification() {
        return Notification.create("sub-0", NotificationCategory.STREAM_LIVE,
                "stream.started", "A streamer you follow is now live",
                "Stream started broadcasting.", OffsetDateTime.now(ZoneOffset.UTC));
    }

    // ── poll / claimAndProcess ──────────────────────────────────────────

    @Test
    @DisplayName("poll with no PENDING jobs completes empty and claims nothing")
    void pollAndProcess_noPendingJobs_completesEmpty() {
        when(jobRepository.pollPending(anyInt())).thenReturn(Flux.empty());

        StepVerifier.create(invokePollerMethod(poller, "claimAndProcess"))
                .verifyComplete();

        verify(jobRepository, never()).claimJob(any(), any(), any());
        verify(subscriptionService, never()).getSubscribers(any(), any());
    }

    @Test
    @DisplayName("poll with a PENDING job claims it and dispatches follower notifications")
    void pollAndProcess_pendingJobs_claimsAndProcesses() {
        FanOutJob job = job();
        List<Subscription> subscribers = subscriptions(3);

        when(jobRepository.pollPending(anyInt())).thenReturn(Flux.just(job));
        when(jobRepository.claimJob(eq(job.getId()), eq(INSTANCE_ID), any()))
                .thenReturn(Mono.just(1L));
        when(subscriptionService.getSubscribers(eq("CHANNEL"), eq(BROADCASTER_SUB)))
                .thenReturn(Flux.fromIterable(subscribers));
        when(notificationService.createForFollower(any(), any()))
                .thenAnswer(inv -> Mono.just(notification()));
        when(dispatcher.deliverToMany(any(), anyInt())).thenReturn(Mono.empty());
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        when(jobRepository.updateState(eq(job.getId()), stateCaptor.capture(), anyInt(), anyInt(),
                any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(invokePollerMethod(poller, "claimAndProcess"))
                .verifyComplete();

        verify(jobRepository).claimJob(eq(job.getId()), eq(INSTANCE_ID), any());
        verify(subscriptionService).getSubscribers("CHANNEL", BROADCASTER_SUB);
        verify(notificationService, times(3)).createForFollower(any(), any());
        verify(dispatcher).deliverToMany(any(), anyInt());
        assertThat(stateCaptor.getValue()).isEqualTo(FanOutJob.STATE_COMPLETED);
        assertThat(job.getProcessedSubscribers()).isEqualTo(3);
    }

    @Test
    @DisplayName("claim returning 0 rows is skipped (another worker claimed the job)")
    void claimAndProcess_claimFails_skipsJob() {
        FanOutJob job = job();

        when(jobRepository.pollPending(anyInt())).thenReturn(Flux.just(job));
        when(jobRepository.claimJob(eq(job.getId()), eq(INSTANCE_ID), any()))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(invokePollerMethod(poller, "claimAndProcess"))
                .verifyComplete();

        verify(subscriptionService, never()).getSubscribers(any(), any());
        verify(jobRepository, never()).updateState(any(), any(), anyInt(), anyInt(), any(), any(), any());
    }

    // ── loadAndDispatch ─────────────────────────────────────────────────

    @Test
    @DisplayName("loadAndDispatch with no subscribers marks the job COMPLETED")
    void loadAndDispatch_noSubscribers_completesJob() {
        FanOutJob job = job();

        when(subscriptionService.getSubscribers(eq("CHANNEL"), eq(BROADCASTER_SUB)))
                .thenReturn(Flux.empty());
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> totalCaptor = ArgumentCaptor.forClass(Integer.class);
        when(jobRepository.updateState(eq(job.getId()), stateCaptor.capture(), anyInt(), anyInt(),
                totalCaptor.capture(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(invokePollerMethod(poller, "loadAndDispatch", job))
                .verifyComplete();

        assertThat(stateCaptor.getValue()).isEqualTo(FanOutJob.STATE_COMPLETED);
        assertThat(totalCaptor.getValue()).isEqualTo(0);
        assertThat(job.getState()).isEqualTo(FanOutJob.STATE_COMPLETED);
        assertThat(job.getTotalSubscribers()).isZero();
        verify(dispatcher, never()).deliverToMany(any(), anyInt());
    }

    @Test
    @DisplayName("loadAndDispatch partitions 25 subscribers into 3 chunks of batch-size 10")
    void loadAndDispatch_withSubscribers_dispatchesInChunks() {
        FanOutJob job = job();

        when(subscriptionService.getSubscribers(eq("CHANNEL"), eq(BROADCASTER_SUB)))
                .thenReturn(Flux.fromIterable(subscriptions(25)));
        when(notificationService.createForFollower(any(), any()))
                .thenAnswer(inv -> Mono.just(notification()));
        when(dispatcher.deliverToMany(any(), anyInt())).thenReturn(Mono.empty());
        when(jobRepository.updateState(any(), any(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(invokePollerMethod(poller, "loadAndDispatch", job))
                .verifyComplete();

        verify(dispatcher, times(3)).deliverToMany(any(), anyInt());
        verify(notificationService, times(25)).createForFollower(any(), any());
        assertThat(job.getProcessedSubscribers()).isEqualTo(25);
        assertThat(job.getTotalSubscribers()).isEqualTo(25);
        assertThat(job.getState()).isEqualTo(FanOutJob.STATE_COMPLETED);
    }

    // ── processChunk ────────────────────────────────────────────────────

    @Test
    @DisplayName("processChunk records progress after a successful dispatch")
    void processChunk_successfulDispatch_recordsProgress() {
        FanOutJob job = job();
        List<Subscription> chunk = subscriptions(3);
        StreamEvent event = new StreamEvent("STREAM_STARTED", BROADCASTER_SUB, "evt-1",
                OffsetDateTime.now(ZoneOffset.UTC), BROADCASTER_SUB, null, null, null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        when(notificationService.createForFollower(any(), any()))
                .thenAnswer(inv -> Mono.just(notification()));
        when(dispatcher.deliverToMany(any(), anyInt())).thenReturn(Mono.empty());

        StepVerifier.create(invokePollerMethod(poller, "processChunk", job, event, chunk, now))
                .verifyComplete();

        verify(dispatcher).deliverToMany(any(), anyInt());
        assertThat(job.getProcessedSubscribers()).isEqualTo(3);
    }

    // ── handleChunkFailure ──────────────────────────────────────────────

    @Test
    @DisplayName("handleChunkFailure on first attempt marks the job FAILED and propagates the error")
    void handleChunkFailure_firstAttempt_retries() {
        FanOutJob job = job();
        List<Subscription> chunk = subscriptions(2);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RuntimeException failure = new RuntimeException("dispatch boom");

        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> retryCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        when(jobRepository.updateState(eq(job.getId()), stateCaptor.capture(), retryCaptor.capture(),
                anyInt(), any(), errorCaptor.capture(), any())).thenReturn(Mono.empty());

        StepVerifier.create(invokePollerMethod(poller, "handleChunkFailure", job, chunk, failure, now))
                .expectError(RuntimeException.class)
                .verify();

        assertThat(stateCaptor.getValue()).isEqualTo(FanOutJob.STATE_FAILED);
        assertThat(retryCaptor.getValue()).isEqualTo(1);
        assertThat(errorCaptor.getValue()).isEqualTo("dispatch boom");
        assertThat(job.getRetryCount()).isEqualTo(1);
        assertThat(job.getState()).isEqualTo(FanOutJob.STATE_FAILED);
        assertThat(job.getLastError()).isEqualTo("dispatch boom");
    }

    @Test
    @DisplayName("handleChunkFailure after max retries marks the job DEAD (DLQ)")
    void handleChunkFailure_maxRetriesExceeded_marksDead() {
        FanOutJob job = job();
        job.setRetryCount(3);
        List<Subscription> chunk = subscriptions(2);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RuntimeException failure = new RuntimeException("final failure");

        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> retryCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        when(jobRepository.updateState(eq(job.getId()), stateCaptor.capture(), retryCaptor.capture(),
                anyInt(), any(), errorCaptor.capture(), any())).thenReturn(Mono.empty());

        StepVerifier.create(invokePollerMethod(poller, "handleChunkFailure", job, chunk, failure, now))
                .verifyComplete();

        assertThat(stateCaptor.getValue()).isEqualTo(FanOutJob.STATE_DEAD);
        assertThat(retryCaptor.getValue()).isEqualTo(4);
        assertThat(errorCaptor.getValue()).isEqualTo("final failure");
        assertThat(job.getState()).isEqualTo(FanOutJob.STATE_DEAD);
    }
}
