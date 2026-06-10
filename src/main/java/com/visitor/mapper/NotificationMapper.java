package com.visitor.mapper;

import com.visitor.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotificationMapper {
    void insert(Notification notification);
    List<Notification> findByUserId(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);
    long countUnreadByUserId(@Param("userId") Long userId);
    void markAsRead(@Param("id") Long id);
}
