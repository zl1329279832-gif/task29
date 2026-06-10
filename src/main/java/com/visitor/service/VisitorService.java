package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.VisitorMapper;
import com.visitor.model.dto.VisitorRegisterRequest;
import com.visitor.model.entity.Visitor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class VisitorService {

    private final VisitorMapper visitorMapper;

    /**
     * Register a new visitor or find existing one by phone
     */
    public Visitor registerOrFind(VisitorRegisterRequest request) {
        // Try to find existing visitor by phone
        if (StringUtils.hasText(request.getPhone())) {
            Visitor existing = visitorMapper.findByPhone(request.getPhone());
            if (existing != null) {
                return existing;
            }
        }

        // Create new visitor
        Visitor visitor = Visitor.builder()
                .name(request.getName())
                .idCard(request.getIdCard())
                .phone(request.getPhone())
                .company(request.getCompany())
                .photoUrl(request.getPhotoUrl())
                .visitCount(0)
                .build();
        visitorMapper.insert(visitor);
        return visitor;
    }

    public Visitor getById(Long id) {
        Visitor visitor = visitorMapper.selectById(id);
        if (visitor == null) {
            throw new BizException(ErrorCode.VISITOR_NOT_FOUND);
        }
        return visitor;
    }

    public Page<Visitor> list(String keyword, int page, int size) {
        LambdaQueryWrapper<Visitor> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Visitor::getName, keyword)
                    .or().like(Visitor::getPhone, keyword)
                    .or().like(Visitor::getCompany, keyword);
        }
        wrapper.orderByDesc(Visitor::getCreatedAt);
        return visitorMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public void incrementVisitCount(Long visitorId) {
        Visitor visitor = visitorMapper.selectById(visitorId);
        if (visitor != null) {
            visitor.setVisitCount(visitor.getVisitCount() + 1);
            visitorMapper.updateById(visitor);
        }
    }
}
