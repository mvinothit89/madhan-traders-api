package com.mt.salesapp.service;

import com.mt.salesapp.model.AppUser;
import com.mt.salesapp.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AppUserService {

    @Autowired
    private AppUserRepository appUserRepository;

    public List<AppUser> getAllUsers() {
        return appUserRepository.findAll();
    }

    public Optional<AppUser> getUserById(UUID id) {
        return appUserRepository.findById(id);
    }

    public AppUser createUser(AppUser user) {
        if (appUserRepository.existsByPhoneNumber(user.getPhoneNumber())) {
            throw new IllegalArgumentException("Phone number is already registered.");
        }
        return appUserRepository.save(user);
    }

    public AppUser updateUser(UUID id, AppUser userDetails) {
        AppUser user = appUserRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));

        user.setFullName(userDetails.getFullName());
        user.setPhoneNumber(userDetails.getPhoneNumber());
        user.setRole(userDetails.getRole());
        user.setIsActive(userDetails.getIsActive());
        
        if (userDetails.getPasscode() != null && !userDetails.getPasscode().isBlank()) {
            user.setPasscode(userDetails.getPasscode());
        }

        return appUserRepository.save(user);
    }

    public void deleteUser(UUID id) {
        if (!appUserRepository.existsById(id)) {
            throw new RuntimeException("User not found with id: " + id);
        }
        appUserRepository.deleteById(id);
    }
}