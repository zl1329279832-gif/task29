package com.visitor.service;

import com.visitor.common.constant.RedisKeyConstants;
import com.visitor.common.exception.BusinessException;
import com.visitor.dto.request.BlacklistRequest;
import com.visitor.dto.response.BlacklistResponse;
import com.visitor.entity.Blacklist;
import com.visitor.entity.SysUser;
import com.visitor.mapper.BlacklistMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.security.CustomUserDetails;
import com.visitor.service.impl.BlacklistServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlacklistServiceTest {

    @Mock private BlacklistMapper blacklistMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SetOperations<String, String> setOps;

    @InjectMocks
    private BlacklistServiceImpl blacklistService;

    @BeforeEach
    void setUp() {
        CustomUserDetails userDetails = new CustomUserDetails(
                1L, "security1", "password", "Security", "安保部",
                List.of(new SimpleGrantedAuthority("ROLE_SECURITY")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
    }

    @Test
    void add_success() {
        BlacklistRequest request = new BlacklistRequest();
        request.setName("黑名单用户");
        request.setPhone("13900000001");
        request.setIdCard("110101200001011234");
        request.setReason("多次违规");

        when(redisTemplate.opsForSet()).thenReturn(setOps);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setRealName("Security");
        when(sysUserMapper.findById(1L)).thenReturn(user);

        BlacklistResponse result = blacklistService.add(request);

        assertNotNull(result);
        assertEquals("黑名单用户", result.getName());
        verify(blacklistMapper).insert(any(Blacklist.class));
        verify(setOps).add(RedisKeyConstants.BLACKLIST_PHONE, "13900000001");
        verify(setOps).add(RedisKeyConstants.BLACKLIST_IDCARD, "110101200001011234");
    }

    @Test
    void remove_success() {
        Blacklist blacklist = new Blacklist();
        blacklist.setId(1L);
        blacklist.setPhone("13900000001");
        blacklist.setIdCard("110101200001011234");

        when(blacklistMapper.findById(1L)).thenReturn(blacklist);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        blacklistService.remove(1L);

        verify(blacklistMapper).updateStatus(1L, 0);
        verify(setOps).remove(RedisKeyConstants.BLACKLIST_PHONE, "13900000001");
        verify(setOps).remove(RedisKeyConstants.BLACKLIST_IDCARD, "110101200001011234");
    }

    @Test
    void remove_notFound_throwsException() {
        when(blacklistMapper.findById(99L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> blacklistService.remove(99L));
    }

    @Test
    void isBlacklisted_byPhone_returnsTrue() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.isMember(RedisKeyConstants.BLACKLIST_PHONE, "13900000001")).thenReturn(true);

        assertTrue(blacklistService.isBlacklisted("13900000001", null));
    }

    @Test
    void isBlacklisted_byIdCard_returnsTrue() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.isMember(RedisKeyConstants.BLACKLIST_PHONE, "13900000002")).thenReturn(false);
        when(setOps.isMember(RedisKeyConstants.BLACKLIST_IDCARD, "110101200001011234")).thenReturn(true);

        assertTrue(blacklistService.isBlacklisted("13900000002", "110101200001011234"));
    }

    @Test
    void isBlacklisted_notFound_returnsFalse() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.isMember(eq(RedisKeyConstants.BLACKLIST_PHONE), eq("13800000000"))).thenReturn(false);
        when(setOps.isMember(eq(RedisKeyConstants.BLACKLIST_IDCARD), eq("000000000000000000"))).thenReturn(false);

        assertFalse(blacklistService.isBlacklisted("13800000000", "000000000000000000"));
    }
}
