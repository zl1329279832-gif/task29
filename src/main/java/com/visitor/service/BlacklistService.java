package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.BlacklistMapper;
import com.visitor.model.dto.BlacklistRequest;
import com.visitor.model.entity.Blacklist;
import com.visitor.model.entity.SysUser;
import com.visitor.model.enums.BlacklistStatusEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BlacklistService {

    private final BlacklistMapper blacklistMapper;

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
