package com.visitor.service.impl;

import com.visitor.common.constant.RoleConstants;
import com.visitor.entity.Appointment;
import com.visitor.entity.Notification;
import com.visitor.entity.SysUser;
import com.visitor.entity.Visitor;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.NotificationMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.mapper.VisitorMapper;
import com.visitor.service.NotificationService;
import com.visitor.websocket.WebSocketMessage;
import com.visitor.websocket.WebSocketSessionManager;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;
    private final AppointmentMapper appointmentMapper;
    private final VisitorMapper visitorMapper;
    private final SysUserMapper sysUserMapper;
    private final WebSocketSessionManager webSocketSessionManager;

    public NotificationServiceImpl(NotificationMapper notificationMapper, AppointmentMapper appointmentMapper,
                                    VisitorMapper visitorMapper, SysUserMapper sysUserMapper,
                                    WebSocketSessionManager webSocketSessionManager) {
        this.notificationMapper = notificationMapper;
        this.appointmentMapper = appointmentMapper;
        this.visitorMapper = visitorMapper;
        this.sysUserMapper = sysUserMapper;
        this.webSocketSessionManager = webSocketSessionManager;
    }

    @Override
    public void sendVisitorArrival(Long appointmentId) {
        Appointment appointment = appointmentMapper.findById(appointmentId);
        if (appointment == null) return;
        Visitor visitor = visitorMapper.findById(appointment.getVisitorId());
        String visitorName = visitor != null ? visitor.getName() : "未知访客";

        Notification notification = new Notification();
        notification.setTargetUserId(appointment.getHostUserId());
        notification.setType("VISITOR_ARRIVAL");
        notification.setTitle("访客到达通知");
        notification.setContent("您的访客 " + visitorName + " 已到达");
        notification.setIsRead(0);
        notification.setReferenceId(appointmentId);
        notification.setReferenceType("APPOINTMENT");
        saveAndPush(notification);
    }

    @Override
    public void sendAbnormalAlert(String visitorName, String reason) {
        List<SysUser> securityUsers = sysUserMapper.findByRoleCode(RoleConstants.SECURITY);
        for (SysUser user : securityUsers) {
            Notification notification = new Notification();
            notification.setTargetUserId(user.getId());
            notification.setType("ABNORMAL_ALERT");
            notification.setTitle("异常通行告警");
            notification.setContent("异常通行: " + visitorName + " - " + reason);
            notification.setIsRead(0);
            notification.setReferenceType("ABNORMAL_PASS");
            saveAndPush(notification);
        }
    }

    @Override
    public void sendApprovalRemind(Long appointmentId) {
        Appointment appointment = appointmentMapper.findById(appointmentId);
        if (appointment == null) return;

        // Notify supervisors in the same department and security
        SysUser host = sysUserMapper.findById(appointment.getHostUserId());
        if (host != null && host.getDepartment() != null) {
            List<SysUser> supervisors = sysUserMapper.findByRoleCode(RoleConstants.SUPERVISOR);
            for (SysUser supervisor : supervisors) {
                if (host.getDepartment().equals(supervisor.getDepartment())) {
                    Notification notification = new Notification();
                    notification.setTargetUserId(supervisor.getId());
                    notification.setType("APPROVAL_REMIND");
                    notification.setTitle("新预约待审批");
                    notification.setContent(host.getRealName() + " 提交了新的访客预约，请及时审批");
                    notification.setIsRead(0);
                    notification.setReferenceId(appointmentId);
                    notification.setReferenceType("APPOINTMENT");
                    saveAndPush(notification);
                }
            }
        }

        // Also notify security
        List<SysUser> securityUsers = sysUserMapper.findByRoleCode(RoleConstants.SECURITY);
        for (SysUser security : securityUsers) {
            Notification notification = new Notification();
            notification.setTargetUserId(security.getId());
            notification.setType("APPROVAL_REMIND");
            notification.setTitle("新预约待审批");
            notification.setContent("有新的访客预约待审批");
            notification.setIsRead(0);
            notification.setReferenceId(appointmentId);
            notification.setReferenceType("APPOINTMENT");
            saveAndPush(notification);
        }
    }

    @Override
    public void sendAppointmentExpired(Long appointmentId) {
        Appointment appointment = appointmentMapper.findById(appointmentId);
        if (appointment == null) return;

        Notification notification = new Notification();
        notification.setTargetUserId(appointment.getCreatedBy());
        notification.setType("APPOINTMENT_EXPIRED");
        notification.setTitle("预约已过期");
        notification.setContent("您的预约 " + appointment.getAppointmentNo() + " 已过期");
        notification.setIsRead(0);
        notification.setReferenceId(appointmentId);
        notification.setReferenceType("APPOINTMENT");
        saveAndPush(notification);
    }

    @Override
    public void saveAndPush(Notification notification) {
        notificationMapper.insert(notification);
        WebSocketMessage message = new WebSocketMessage(
                notification.getType(), notification.getContent());
        webSocketSessionManager.sendToUser(notification.getTargetUserId(), message);
    }
}
