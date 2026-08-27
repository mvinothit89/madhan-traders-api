package com.mt.salesapp.dto;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class LoginRequest {
    private String username;
    private String password;

    // Constructors, getters, and setters
}