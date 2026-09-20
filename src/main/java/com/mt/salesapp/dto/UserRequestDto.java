package com.mt.salesapp.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class UserRequestDto {
    private String username;
    private String email;
    private String phoneNumber;
    private String passcode;
    private String role;
    private String status;


}