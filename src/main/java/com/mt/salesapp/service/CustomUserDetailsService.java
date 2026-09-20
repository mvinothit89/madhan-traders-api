package com.mt.salesapp.service;

import com.mt.salesapp.dto.CustomUserDetails;
import com.mt.salesapp.model.AppUser;
import com.mt.salesapp.repository.AppUserRepository;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    public CustomUserDetailsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String phoneNumber) throws UsernameNotFoundException {
        // 1. Fetch your AppUser from the database using phone number (or username)
        AppUser appUser = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with phone number: " + phoneNumber));

        // 2. Wrap it in your CustomUserDetails class (DO NOT return Spring's default User.builder())
        return new CustomUserDetails(appUser);
    }


}