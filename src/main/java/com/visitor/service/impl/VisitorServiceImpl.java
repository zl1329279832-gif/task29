package com.visitor.service.impl;

import com.visitor.common.exception.BusinessException;
import com.visitor.common.result.PageResult;
import com.visitor.dto.request.VisitorRegisterRequest;
import com.visitor.dto.response.VisitorResponse;
import com.visitor.entity.Visitor;
import com.visitor.mapper.VisitorMapper;
import com.visitor.service.VisitorService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class VisitorServiceImpl implements VisitorService {

    private final VisitorMapper visitorMapper;

    public VisitorServiceImpl(VisitorMapper visitorMapper) {
        this.visitorMapper = visitorMapper;
    }

    @Override
    public VisitorResponse register(VisitorRegisterRequest request) {
        Visitor existing = visitorMapper.findByPhone(request.getPhone());
        if (existing != null) {
            existing.setName(request.getName());
            if (request.getIdCard() != null) existing.setIdCard(request.getIdCard());
            if (request.getCompany() != null) existing.setCompany(request.getCompany());
            if (request.getEmail() != null) existing.setEmail(request.getEmail());
            if (request.getPhotoUrl() != null) existing.setPhotoUrl(request.getPhotoUrl());
            visitorMapper.update(existing);
            return toResponse(existing);
        }
        Visitor visitor = new Visitor();
        BeanUtils.copyProperties(request, visitor);
        visitorMapper.insert(visitor);
        return toResponse(visitor);
    }

    @Override
    public VisitorResponse getById(Long id) {
        Visitor visitor = visitorMapper.findById(id);
        if (visitor == null) {
            throw new BusinessException("访客不存在");
        }
        return toResponse(visitor);
    }

    @Override
    public VisitorResponse update(Long id, VisitorRegisterRequest request) {
        Visitor visitor = visitorMapper.findById(id);
        if (visitor == null) {
            throw new BusinessException("访客不存在");
        }
        BeanUtils.copyProperties(request, visitor);
        visitor.setId(id);
        visitorMapper.update(visitor);
        return toResponse(visitor);
    }

    @Override
    public PageResult<VisitorResponse> list(int page, int size) {
        int offset = (page - 1) * size;
        List<Visitor> visitors = visitorMapper.findAll(offset, size);
        long total = visitorMapper.count();
        List<VisitorResponse> records = visitors.stream().map(this::toResponse).collect(Collectors.toList());
        return PageResult.of(records, total, page, size);
    }

    @Override
    public PageResult<VisitorResponse> search(String keyword, int page, int size) {
        int offset = (page - 1) * size;
        List<Visitor> visitors = visitorMapper.search(keyword, offset, size);
        long total = visitorMapper.searchCount(keyword);
        List<VisitorResponse> records = visitors.stream().map(this::toResponse).collect(Collectors.toList());
        return PageResult.of(records, total, page, size);
    }

    private VisitorResponse toResponse(Visitor visitor) {
        VisitorResponse response = new VisitorResponse();
        BeanUtils.copyProperties(visitor, response);
        return response;
    }
}
