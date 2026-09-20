package com.mt.salesapp.controller;

import com.mt.salesapp.dto.CustomUserDetails;
import com.mt.salesapp.dto.LoginRequest;
import com.mt.salesapp.dto.UserResponseDto;
import com.mt.salesapp.model.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponseDto> login(@RequestBody LoginRequest request,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        // 1. Authenticate credentials
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        // 2. Create security context and save to session repository
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        // 3. Extract AppUser from principal
        CustomUserDetails userDetails = (CustomUserDetails) auth.getPrincipal();
        AppUser user = userDetails.getAppUser();

        // 4. Map to Response DTO
        UserResponseDto responseDTO = new UserResponseDto(
                user.getId(),
                user.getFullName(),
                user.getPhoneNumber(),
                user.getRole()
        );

        // 5. Return JSON payload matching your frontend expectation (store.user = data)
        return ResponseEntity.ok(responseDTO);
    }
}
