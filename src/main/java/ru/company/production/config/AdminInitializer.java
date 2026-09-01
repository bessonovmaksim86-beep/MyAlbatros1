package ru.company.production.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;
import ru.company.production.repository.UserRepository;

@Component
@RequiredArgsConstructor
public class AdminInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${application.initial-admin.username}")
    private String username;

    @Value("${application.initial-admin.password}")
    private String password;

    @Override
    public void run(String... args) {
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            return;
        }

        AppUser admin = new AppUser();
        admin.setUsername(username.trim());
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRole(Role.ADMIN);
        admin.setActive(true);

        userRepository.save(admin);
    }
}