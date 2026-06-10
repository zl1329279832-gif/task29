package com.visitor.service;

import com.visitor.entity.Notification;

public interface NotificationService {
    void sendVisitorArrival(Long appointmentId);
    void sendAbnormalAlert(String visitorName, String reason);
    void sendApprovalRemind(Long appointmentId);
    void sendAppointmentExpired(Long appointmentId);
    void saveAndPush(Notification notification);
}
