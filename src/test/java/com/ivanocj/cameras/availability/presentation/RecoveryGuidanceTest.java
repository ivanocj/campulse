package com.ivanocj.cameras.availability.presentation;

import com.ivanocj.cameras.availability.application.model.BatchEntry;
import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.ProbeResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecoveryGuidanceTest {
    @Test
    void timeoutRejectionAndUnknownRemainUnconfirmedRatherThanOffline() {
        for (var status : List.of(ProbeResult.Status.TIMED_OUT, ProbeResult.Status.SERVER_REJECTED, ProbeResult.Status.INCONCLUSIVE)) {
            var view = AvailabilityPresenter.completed(new ProbeResult(status));
            assertFalse(view.recovery().isBlank());
            assertTrue(view.recovery().contains("does not prove"));
            assertFalse(view.headline().contains("offline"));
        }
    }

    @Test
    void accountGuidanceDoesNotInventAnAuthenticationCauseOrExposeNestedDetails() {
        var required = AvailabilityPresenter.completed(new ProbeResult(ProbeResult.Status.AUTH_REQUIRED));
        assertTrue(required.recovery().contains("Yoosee account · single & batch"));
        assertTrue(required.recovery().contains("not a camera password"));
        var rejected = AvailabilityPresenter.completed(new ProbeResult(ProbeResult.Status.AUTH_FAILED));
        assertTrue(rejected.recovery().contains("authorized account login provider"));
        assertTrue(rejected.recovery().contains("not evidence"));
        var failure = new IOException("Remote check could not be completed.", new IOException("PRIVATE_PATH_AND_TOKEN"));
        var view = AvailabilityPresenter.failed(failure);
        assertEquals("Remote check could not be completed.", view.detail());
        assertFalse((view.detail() + view.recovery() + view.p2pInformation()).contains("PRIVATE_PATH_AND_TOKEN"));
        assertFalse(view.recovery().contains("expired"), "A redacted failure cannot be diagnosed from a guessed cause");
    }

    @Test
    void recoveryIsSeparateFromOriginalFailureMessagesAndBatchDetails() {
        var failure = AvailabilityPresenter.failed(new IOException("Original socket failure."));
        assertEquals("Original socket failure.", failure.detail());
        assertTrue(failure.recovery().contains("network"));
        var invalid = AvailabilityPresenter.batchInvalid("Original CSV header failure.");
        assertEquals("Original CSV header failure.", invalid.detail());
        assertTrue(invalid.recovery().contains("Serial,Description"));
        var batchFailure = AvailabilityPresenter.batchFailed(new IOException("Original file failure."));
        assertEquals("Original file failure.", batchFailure.detail());
        assertTrue(batchFailure.recovery().contains("readable"));
        var row = new BatchResult(new BatchEntry(2, "broken", Optional.empty(), ""), BatchResult.State.FAILED,
                Optional.empty(), "Original row failure.");
        assertEquals("Original row failure.", AvailabilityPresenter.batchResult(row).detail());
        assertFalse(AvailabilityPresenter.batchResult(row).recovery().isBlank());
    }

    @Test
    void freshProgressSuccessAndCancelledViewsHaveNoOldRecoveryAdvice() {
        for (var view : List.of(AvailabilityPresenter.idle(), AvailabilityPresenter.checking(), AvailabilityPresenter.cancelling(),
                AvailabilityPresenter.invalid("Fix the local input."), AvailabilityPresenter.batchLoading(),
                AvailabilityPresenter.completed(new ProbeResult(ProbeResult.Status.REACHABLE_P2P)),
                AvailabilityPresenter.completed(new ProbeResult(ProbeResult.Status.CANCELLED)))) {
            assertEquals("", view.recovery());
        }
    }
}
