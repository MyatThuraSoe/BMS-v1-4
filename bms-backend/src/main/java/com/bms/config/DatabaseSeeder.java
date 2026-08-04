package com.bms.config;

import com.bms.entity.Role;
import com.bms.repository.RoleRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    private final RoleRepository roleRepository;

    public DatabaseSeeder(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        // Loop through all the roles in your Enum and create them if they are missing
        for (Role.RoleName roleName : Role.RoleName.values()) {
            if (roleRepository.findByName(roleName).isEmpty()) {
                Role newRole = new Role();
                newRole.setName(roleName);
                roleRepository.save(newRole);
            }
        }
    }
}