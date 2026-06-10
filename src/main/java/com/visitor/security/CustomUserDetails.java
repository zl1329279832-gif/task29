package com.visitor.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

@Getter
public class CustomUserDetails extends User {

    private final Long userId;
    private final String realName;
    private final String department;

    public CustomUserDetails(Long userId, String username, String password, String realName,
                             String department, Collection<? extends GrantedAuthority> authorities) {
        super(username, password, authorities);
        this.userId = userId;
        this.realName = realName;
        this.department = department;
    }
}
