package com.visitor.integration;

import com.visitor.common.constant.AppointmentStatus;
import com.visitor.common.util.PassCodeGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppointmentFlowIntegrationTest {

    @Test
    void fullAppointmentLifecycle_stateMachine() {
        // Test the full state machine flow
        AppointmentStatus status = AppointmentStatus.PENDING_APPROVAL;

        // Step 1: Approve
        assertTrue(status.canTransitionTo(AppointmentStatus.APPROVED));
        status = AppointmentStatus.APPROVED;

        // Step 2: Check in
        assertTrue(status.canTransitionTo(AppointmentStatus.CHECKED_IN));
        status = AppointmentStatus.CHECKED_IN;

        // Step 3: Check out
        assertTrue(status.canTransitionTo(AppointmentStatus.CHECKED_OUT));
        status = AppointmentStatus.CHECKED_OUT;

        // Cannot go back
        assertFalse(status.canTransitionTo(AppointmentStatus.CHECKED_IN));
        assertFalse(status.canTransitionTo(AppointmentStatus.APPROVED));
    }

    @Test
    void rejectedAppointment_cannotProceed() {
        AppointmentStatus status = AppointmentStatus.REJECTED;
        assertFalse(status.canTransitionTo(AppointmentStatus.APPROVED));
        assertFalse(status.canTransitionTo(AppointmentStatus.CHECKED_IN));
    }

    @Test
    void cancelledAppointment_cannotProceed() {
        AppointmentStatus status = AppointmentStatus.CANCELLED;
        assertFalse(status.canTransitionTo(AppointmentStatus.APPROVED));
        assertFalse(status.canTransitionTo(AppointmentStatus.CHECKED_IN));
    }

    @Test
    void expiredAppointment_cannotProceed() {
        AppointmentStatus status = AppointmentStatus.EXPIRED;
        assertFalse(status.canTransitionTo(AppointmentStatus.APPROVED));
        assertFalse(status.canTransitionTo(AppointmentStatus.CHECKED_IN));
    }

    @Test
    void rescheduledAppointment_cannotProceed() {
        AppointmentStatus status = AppointmentStatus.RESCHEDULED;
        assertFalse(status.canTransitionTo(AppointmentStatus.APPROVED));
    }

    @Test
    void validateTransition_throwsOnInvalid() {
        AppointmentStatus status = AppointmentStatus.PENDING_APPROVAL;
        assertThrows(IllegalStateException.class, () -> status.validateTransition(AppointmentStatus.CHECKED_IN));
    }
}
