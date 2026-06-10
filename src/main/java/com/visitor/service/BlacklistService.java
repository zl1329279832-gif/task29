package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.BlacklistMapper;
import com.visitor.mapper.VisitorMapper;
import com.visitor.model.dto.BlacklistRequest;
import com.visitor.model.entity.Blacklist;
import com.visitor.model.entity.SysUser;
import com.visitor.model.entity.Visitor;
import com.visitor.model.enums.BlacklistStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
public class BlacklistService {

    private final BlacklistMapper blacklistMapper;
    private final VisitorMapper visitorMapper;
    private final AppointmentService appointmentService;

    public BlacklistService(BlacklistMapper blacklistMapper,
                            VisitorMapper visitorMapper,
                            @Lazy AppointmentService appointmentService) {
        this.blacklistMapper = blacklistMapper;
        this.visitorMapper = visitorMapper;
        this.appointmentService = appointmentService;
    }

    /**
     * Check if a person is on the blacklist
     */
    public Blacklist check(String name, String idCard, String phone) {
        return blacklistMapper.checkBlacklist(name, idCard, phone);
    }

    /**
     * Assert that a visitor is NOT on the blacklist, throw if found
     */
    public void assertNotBlacklisted(String name, String idCard, String phone) {
        Blacklist bl = check(name, idCard, phone);
        if (bl != null) {
            throw new BizException(ErrorCode.BLACKLIST_HIT, bl.getReason());
        }
    }

    /**
     * Add a visitor to the blacklist and cascade-cancel all their active appointments.
     */
    @Transactional
    public Blacklist add(BlacklistRequest request) {
        // Check if already blacklisted
        Blacklist existing = check(request.getName(), request.getIdCard(), request.getPhone());
        if (existing != null) {
            throw new BizException(ErrorCode.VISITOR_BLACKLISTED);
        }

        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Blacklist blacklist = Blacklist.builder()
                .name(request.getName())
                .idCard(request.getIdCard())
                .phone(request.getPhone())
                .reason(request.getReason())
                .expireAt(request.getExpireAt())
                .status(BlacklistStatusEnum.ACTIVE)
                .build();
        blacklistMapper.insert(blacklist);

        // Cascade: find matching visitors and cancel all their active appointments + revoke codes
        Set<Long> visitorIds = new HashSet<>();
        if (StringUtils.hasText(request.getPhone())) {
            Visitor byPhone = visitorMapper.findByPhone(request.getPhone());
            if (byPhone != null) visitorIds.add(byPhone.getId());
        }
        if (StringUtils.hasText(request.getIdCard())) {
            Visitor byIdCard = visitorMapper.findByIdCard(request.getIdCard());
            if (byIdCard != null) visitorIds.add(byIdCard.getId());
        }

        int cancelledTotal = 0;
        for (Long visitorId : visitorIds) {
            int cancelled = appointmentService.cancelAllForVisitor(visitorId);
            cancelledTotal += cancelled;
        }
        if (cancelledTotal > 0) {
            log.info("Blacklist cascade: cancelled {} appointments for blacklisted visitor {}",
                    cancelledTotal, request.getName());
        }

        return blacklist;
    }

    public Page<Blacklist> list(String keyword, int page, int size) {
        LambdaQueryWrapper<Blacklist> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Blacklist::getStatus, BlacklistStatusEnum.ACTIVE);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Blacklist::getName, keyword)
                    .or().like(Blacklist::getPhone, keyword));
        }
        wrapper.orderByDesc(Blacklist::getCreatedAt);
        return blacklistMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public void remove(Long id) {
        Blacklist blacklist = blacklistMapper.selectById(id);
        if (blacklist == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "黑名单记录不存在");
        }
        blacklist.setStatus(BlacklistStatusEnum.REMOVED);
        blacklistMapper.updateById(blacklist);
    }
}
