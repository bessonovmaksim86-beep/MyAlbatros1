package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.CreateUserForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;
import ru.company.production.repository.UserRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<AppUser> findAll() {
        return userRepository.findAll();
    }

    @Transactional
    public void create(CreateUserForm form) {
        String username = form.getUsername().trim();

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException(
                    "Пользователь с таким логином уже существует"
            );
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        user.setRole(form.getRole());
        user.setActive(true);

        userRepository.save(user);
    }

    @Transactional
    public void delete(Long id, String currentUsername) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException("Пользователь не найден"));

        if (user.getUsername().equalsIgnoreCase(currentUsername)) {
            throw new IllegalArgumentException(
                    "Нельзя удалить собственную учётную запись"
            );
        }

        if (user.getRole() == Role.ADMIN
                && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new IllegalArgumentException(
                    "Нельзя удалить последнего администратора"
            );
        }

        userRepository.delete(user);
    }
}