package com.visitor.service.impl;

import com.visitor.common.constant.RedisKeyConstants;
import com.visitor.common.exception.BusinessException;
import com.visitor.common.result.PageResult;
import com.visitor.common.util.SecurityUtils;
import com.visitor.dto.request.BlacklistRequest;
import com.visitor.dto.response.BlacklistResponse;
import com.visitor.entity.Blacklist;
import com.visitor.entity.SysUser;
import com.visitor.mapper.BlacklistMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.service.BlacklistService;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class BlacklistServiceImpl implements BlacklistService {

    private final BlacklistMapper blacklistMapper;
    private final SysUserMapper sysUserMapper;
    private final StringRedisTemplate redisTemplate;

    public BlacklistServiceImpl(BlacklistMapper blacklistMapper, SysUserMapper sysUserMapper,
                                 StringRedisTemplate redisTemplate) {
        this.blacklistMapper = blacklistMapper;
        this.sysUserMapper = sysUserMapper;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        refreshCache();
    }

    @Override
    public BlacklistResponse add(BlacklistRequest request) {
        Blacklist blacklist = new Blacklist();
        blacklist.setVisitorId(request.getVisitorId());
        blacklist.setName(request.getName());
        blacklist.setIdCard(request.getIdCard());
        blacklist.setPhone(request.getPhone());
        blacklist.setReason(request.getReason());
        blacklist.setStatus(1);
        blacklist.setCreatedBy(SecurityUtils.getCurrentUserId());
        blacklistMapper.insert(blacklist);

        // Update Redis cache
        if (StringUtils.hasText(request.getPhone())) {
            redisTemplate.opsForSet().add(RedisKeyConstants.BLACKLIST_PHONE, request.getPhone());
        }
        if (StringUtils.hasText(request.getIdCard())) {
            redisTemplate.opsForSet().add(RedisKeyConstants.BLACKLIST_IDCARD, request.getIdCard());
        }

        return toResponse(blacklist);
    }

    @Override
    public void remove(Long id) {
        Blacklist blacklist = blacklistMapper.findById(id);
        if (blacklist == null) throw new BusinessException("黑名单记录不存在");

        blacklistMapper.updateStatus(id, 0);

        // Update Redis cache
        if (StringUtils.hasText(blacklist.getPhone())) {
            redisTemplate.opsForSet().remove(RedisKeyConstants.BLACKLIST_PHONE, blacklist.getPhone());
        }
        if (StringUtils.hasText(blacklist.getIdCard())) {
            redisTemplate.opsForSet().remove(RedisKeyConstants.BLACKLIST_IDCARD, blacklist.getIdCard());
        }
    }

    @Override
    public PageResult<BlacklistResponse> list(int page, int size) {
        int offset = (page - 1) * size;
        List<Blacklist> list = blacklistMapper.findActive(offset, size);
        long total = blacklistMapper.countActive();
        List<BlacklistResponse> records = list.stream().map(this::toResponse).collect(Collectors.toList());
        return PageResult.of(records, total, page, size);
    }

    @Override
    public boolean isBlacklisted(String phone, String idCard) {
        if (StringUtils.hasText(phone)) {
            Boolean isMember = redisTemplate.opsForSet().isMember(RedisKeyConstants.BLACKLIST_PHONE, phone);
            if (Boolean.TRUE.equals(isMember)) return true;
        }
        if (StringUtils.hasText(idCard)) {
            Boolean isMember = redisTemplate.opsForSet().isMember(RedisKeyConstants.BLACKLIST_IDCARD, idCard);
            if (Boolean.TRUE.equals(isMember)) return true;
        }
        return false;
    }

    @Override
    public void refreshCache() {
        try {
            redisTemplate.delete(RedisKeyConstants.BLACKLIST_PHONE);
            redisTemplate.delete(RedisKeyConstants.BLACKLIST_IDCARD);

            List<Blacklist> activeList = blacklistMapper.findAllActive();
            for (Blacklist b : activeList) {
                if (StringUtils.hasText(b.getPhone())) {
                    redisTemplate.opsForSet().add(RedisKeyConstants.BLACKLIST_PHONE, b.getPhone());
                }
                if (StringUtils.hasText(b.getIdCard())) {
                    redisTemplate.opsForSet().add(RedisKeyConstants.BLACKLIST_IDCARD, b.getIdCard());
                }
            }
        } catch (Exception e) {
            // Redis not available during startup is acceptable
        }
    }

    private BlacklistResponse toResponse(Blacklist blacklist) {
        BlacklistResponse response = new BlacklistResponse();
        response.setId(blacklist.getId());
        response.setVisitorId(blacklist.getVisitorId());
        response.setName(blacklist.getName());
        response.setIdCard(blacklist.getIdCard());
        response.setPhone(blacklist.getPhone());
        response.setReason(blacklist.getReason());
        response.setStatus(blacklist.getStatus());
        SysUser creator = sysUserMapper.findById(blacklist.getCreatedBy());
        if (creator != null) response.setCreatedByName(creator.getRealName());
        response.setCreatedAt(blacklist.getCreatedAt());
        return response;
    }
}
