package com.visitor.service;

import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.BlacklistMapper;
import com.visitor.model.dto.BlacklistRequest;
import com.visitor.model.entity.Blacklist;
import com.visitor.model.enums.BlacklistStatusEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlacklistServiceTest {

    @InjectMocks
    private BlacklistService blacklistService;

    @Mock
    private BlacklistMapper blacklistMapper;

    @Test
    void testCheck_NotBlacklisted() {
        when(blacklistMapper.checkBlacklist("Li Si", null, "13800138000")).thenReturn(null);

        Blacklist result = blacklistService.check("Li Si", null, "13800138000");

        assertNull(result);
    }

    @Test
    void testCheck_Blacklisted() {
        Blacklist bl = Blacklist.builder()
                .id(1L).name("Bad Person").reason("Violence")
                .status(BlacklistStatusEnum.ACTIVE).build();

        when(blacklistMapper.checkBlacklist("Bad Person", null, null)).thenReturn(bl);

        Blacklist result = blacklistService.check("Bad Person", null, null);

        assertNotNull(result);
        assertEquals("Violence", result.getReason());
    }

    @Test
    void testAssertNotBlacklisted_Pass() {
        when(blacklistMapper.checkBlacklist("Li Si", null, "13800138000")).thenReturn(null);

        assertDoesNotThrow(() ->
                blacklistService.assertNotBlacklisted("Li Si", null, "13800138000"));
    }

    @Test
    void testAssertNotBlacklisted_Fail() {
        Blacklist bl = Blacklist.builder()
                .id(1L).name("Bad Person").reason("Violence")
                .status(BlacklistStatusEnum.ACTIVE).build();

        when(blacklistMapper.checkBlacklist("Bad Person", null, null)).thenReturn(bl);

        BizException ex = assertThrows(BizException.class,
                () -> blacklistService.assertNotBlacklisted("Bad Person", null, null));
        assertEquals(ErrorCode.BLACKLIST_HIT, ex.getErrorCode());
    }

    @Test
    void testAdd_Success() {
        var auth = new UsernamePasswordAuthenticationToken(
                "admin1", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        BlacklistRequest request = new BlacklistRequest();
        request.setName("Bad Person");
        request.setPhone("13900139000");
        request.setReason("Violence");

        when(blacklistMapper.checkBlacklist(anyString(), any(), anyString())).thenReturn(null);
        when(blacklistMapper.insert(any())).thenReturn(1);

        Blacklist result = blacklistService.add(request);

        assertNotNull(result);
        assertEquals("Bad Person", result.getName());
        assertEquals(BlacklistStatusEnum.ACTIVE, result.getStatus());
    }

    @Test
    void testAdd_AlreadyBlacklisted() {
        var auth = new UsernamePasswordAuthenticationToken(
                "admin1", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        BlacklistRequest request = new BlacklistRequest();
        request.setName("Already Listed");

        Blacklist existing = Blacklist.builder()
                .id(1L).name("Already Listed").status(BlacklistStatusEnum.ACTIVE).build();

        when(blacklistMapper.checkBlacklist(eq("Already Listed"), any(), any())).thenReturn(existing);

        BizException ex = assertThrows(BizException.class, () -> blacklistService.add(request));
        assertEquals(ErrorCode.VISITOR_BLACKLISTED, ex.getErrorCode());
    }

    @Test
    void testRemove() {
        Blacklist blacklist = Blacklist.builder()
                .id(1L).status(BlacklistStatusEnum.ACTIVE).build();

        when(blacklistMapper.selectById(1L)).thenReturn(blacklist);

        blacklistService.remove(1L);

        assertEquals(BlacklistStatusEnum.REMOVED, blacklist.getStatus());
        verify(blacklistMapper).updateById(blacklist);
    }
}
