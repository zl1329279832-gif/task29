package com.visitor.common.constant;

import java.util.Set;

public enum AppointmentStatus {
    PENDING_APPROVAL(Set.of("APPROVED", "REJECTED", "CANCELLED")),
    APPROVED(Set.of("CHECKED_IN", "CANCELLED", "EXPIRED", "RESCHEDULED")),
    REJECTED(Set.of()),
    CANCELLED(Set.of()),
    CHECKED_IN(Set.of("CHECKED_OUT")),
    CHECKED_OUT(Set.of()),
    EXPIRED(Set.of()),
    RESCHEDULED(Set.of());

    private final Set<String> allowedTransitions;

    AppointmentStatus(Set<String> allowedTransitions) {
        this.allowedTransitions = allowedTransitions;
    }

    public boolean canTransitionTo(AppointmentStatus target) {
        return allowedTransitions.contains(target.name());
    }

    public void validateTransition(AppointmentStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("非法状态转换: " + this.name() + " -> " + target.name());
        }
    }
}
