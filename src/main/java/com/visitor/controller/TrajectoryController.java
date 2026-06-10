package com.visitor.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.model.vo.ApiResponse;
import com.visitor.model.vo.TrajectoryVO;
import com.visitor.service.TrajectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trajectories")
@RequiredArgsConstructor
public class TrajectoryController {

    private final TrajectoryService trajectoryService;

    @GetMapping
    public ApiResponse<Page<TrajectoryVO>> list(
            @RequestParam(required = false) Long visitorId,
            @RequestParam(required = false) Long appointmentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                trajectoryService.getTrajectoryPaged(visitorId, appointmentId, page, size));
    }
}
