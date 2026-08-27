package com.mt.salesapp.model;

import com.mt.salesapp.model.AppUser;
import java.util.Map;

public class UserMapper {

    public static AppUser mapToEntity(Map<String, Object> payload) {
        AppUser user = new AppUser();
        
        // Map username to full_name
        if (payload.get("username") != null) {
            user.setFullName((String) payload.get("username"));
        }
        
        // Map status ("ACTIVE" / "INACTIVE") to boolean isActive
        String status = (String) payload.get("status");
        if (status != null) {
            user.setIsActive("ACTIVE".equalsIgnoreCase(status));
        } else {
            user.setIsActive(true); // Default fallback
        }
        
        // Map role
        if (payload.get("role") != null) {
            user.setRole((String) payload.get("role"));
        }
        
        // Handle fields that might be missing in the JSON map but required by the entity
        // (Ensure these are passed in your request or given default/generated values)
        if (payload.get("phoneNumber") != null) {
            user.setPhoneNumber((String) payload.get("phoneNumber"));
        }
        
        if (payload.get("passcode") != null) {
            user.setPasscode((String) payload.get("passcode"));
        }
        
        return user;
    }
}