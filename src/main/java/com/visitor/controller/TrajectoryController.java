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

    @GetMapping("/appointment/{appointmentId}")
    public ApiResponse<TrajectoryVO> getAppointmentTrajectory(
            @PathVariable Long appointmentId) {
        return ApiResponse.success(trajectoryService.getTrajectory(appointmentId));
    }

    @GetMapping("/visitor/{visitorId}")
    public ApiResponse<Page<TrajectoryVO>> getVisitorTrajectory(
            @PathVariable Long visitorId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(trajectoryService.getVisitorTrajectory(visitorId, page, size));
    }
}
