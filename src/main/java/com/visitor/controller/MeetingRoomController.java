package com.visitor.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.model.dto.MeetingRoomCreateRequest;
import com.visitor.model.entity.MeetingRoom;
import com.visitor.model.vo.ApiResponse;
import com.visitor.service.MeetingRoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/meeting-rooms")
@RequiredArgsConstructor
public class MeetingRoomController {

    private final MeetingRoomService meetingRoomService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<MeetingRoom> create(@Valid @RequestBody MeetingRoomCreateRequest request) {
        return ApiResponse.success(meetingRoomService.create(request));
    }

    @GetMapping
    public ApiResponse<Page<MeetingRoom>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(meetingRoomService.list(keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<MeetingRoom> detail(@PathVariable Long id) {
        return ApiResponse.success(meetingRoomService.getById(id));
    }
}
