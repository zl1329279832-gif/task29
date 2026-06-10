package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.MeetingRoomMapper;
import com.visitor.model.dto.MeetingRoomCreateRequest;
import com.visitor.model.entity.MeetingRoom;
import com.visitor.model.enums.AreaStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingRoomService {

    private final MeetingRoomMapper meetingRoomMapper;
    private final AreaService areaService;

    public MeetingRoom create(MeetingRoomCreateRequest request) {
        // Validate area exists
        areaService.getById(request.getAreaId());

        LambdaQueryWrapper<MeetingRoom> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MeetingRoom::getRoomCode, request.getRoomCode());
        if (meetingRoomMapper.selectCount(wrapper) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "会议室编码已存在: " + request.getRoomCode());
        }

        MeetingRoom room = MeetingRoom.builder()
                .roomCode(request.getRoomCode())
                .roomName(request.getRoomName())
                .areaId(request.getAreaId())
                .buildingName(request.getBuildingName())
                .floorInfo(request.getFloorInfo())
                .capacity(request.getCapacity())
                .status(AreaStatusEnum.ACTIVE)
                .build();
        meetingRoomMapper.insert(room);
        log.info("Created meeting room: {} ({})", room.getRoomName(), room.getRoomCode());
        return room;
    }

    public MeetingRoom getById(Long id) {
        MeetingRoom room = meetingRoomMapper.selectById(id);
        if (room == null) {
            throw new BizException(ErrorCode.MEETING_ROOM_NOT_FOUND);
        }
        return room;
    }

    public Page<MeetingRoom> list(String keyword, int page, int size) {
        LambdaQueryWrapper<MeetingRoom> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(MeetingRoom::getRoomName, keyword)
                    .or().like(MeetingRoom::getRoomCode, keyword));
        }
        wrapper.orderByAsc(MeetingRoom::getRoomCode);

        List<MeetingRoom> all = meetingRoomMapper.selectList(wrapper);
        Page<MeetingRoom> result = new Page<>(page, size);
        int start = (page - 1) * size;
        int end = Math.min(start + size, all.size());
        if (start < all.size()) {
            result.setRecords(all.subList(start, end));
        }
        result.setTotal(all.size());
        return result;
    }

    public Long getAreaIdByRoomId(Long roomId) {
        MeetingRoom room = getById(roomId);
        return room.getAreaId();
    }
}
