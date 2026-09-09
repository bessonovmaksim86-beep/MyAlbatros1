package ru.company.production.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.PasswordVaultService;

@Component
@RequiredArgsConstructor
public class AdminInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordVaultService passwordVaultService;

    @Value("${application.initial-admin.username}")
    private String username;

    @Value("${application.initial-admin.password}")
    private String password;

    @Override
    @Transactional
    public void run(String... args) {
        AppUser admin = userRepository
                .findByUsernameIgnoreCase(username)
                .orElse(null);

        if (admin == null) {
            admin = new AppUser();
            admin.setUsername(username.trim());
            admin.setFullName("Системный администратор");
            admin.setSpecialty("Администратор системы");
            admin.setOrganizationUnit(null);
            admin.setRole(Role.ADMIN);
            admin.setActive(true);
            admin.setPasswordHash(
                    passwordEncoder.encode(password)
            );
            admin.setPasswordCiphertext(
                    passwordVaultService.encrypt(password)
            );

            userRepository.save(admin);
            return;
        }

        boolean changed = false;

        if (
                admin.getFullName() == null
                        || admin.getFullName().isBlank()
        ) {
            admin.setFullName("Системный администратор");
            changed = true;
        }

        if (
                admin.getSpecialty() == null
                        || admin.getSpecialty().isBlank()
        ) {
            admin.setSpecialty("Администратор системы");
            changed = true;
        }

        /*
         * BCrypt нельзя расшифровать, поэтому для старого администратора
         * устанавливается пароль из INITIAL_ADMIN_PASSWORD.
         */
        if (
                admin.getPasswordCiphertext() == null
                        || admin.getPasswordCiphertext().isBlank()
        ) {
            admin.setPasswordHash(
                    passwordEncoder.encode(password)
            );
            admin.setPasswordCiphertext(
                    passwordVaultService.encrypt(password)
            );
            changed = true;
        }

        if (changed) {
            userRepository.save(admin);
        }
    }
}