package com.mt.salesapp.dto;

import java.util.UUID;

public class UserResponseDto {
    private UUID id;
    private String fullName;
    private String phoneNumber;
    private String role;

    public UserResponseDto(UUID id, String fullName, String phoneNumber, String role) {
        this.id = id;
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
        this.role = role;
    }

    // Getters
    public UUID getId() { return id; }
    public String getFullName() { return fullName; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getRole() { return role; }
}
