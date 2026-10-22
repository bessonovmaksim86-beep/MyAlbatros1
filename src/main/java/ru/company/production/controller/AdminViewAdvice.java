package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.AdminConsoleService;

import java.util.Locale;

/**
 * Данные текущего пользователя для всех страниц модуля администрирования
 * (верхняя панель). Состав тот же, что у {@code TechnologyViewAdvice}, чтобы
 * админка выглядела разделом системы, а не отдельным самописным интерфейсом.
 *
 * Здесь же отдаётся список ролей для бокового меню: переход в чужой интерфейс
 * нужен на каждой странице модуля, а не только на странице «Роли и вход»,
 * поэтому меню живёт во фрагменте {@code admin/fragments/layout} и получает
 * данные отсюда.
 */
@ControllerAdvice(assignableTypes = {
        AdminModuleController.class,
        AdminUserController.class,
        AdminSwitchController.class,
        AdminHolidayController.class,
        AdminDataCleanupController.class
})
@RequiredArgsConstructor
public class AdminViewAdvice {

    private final UserRepository userRepository;
    private final AdminConsoleService consoleService;

    @ModelAttribute
    public void addCurrentUser(
            Authentication authentication,
            Model model
    ) {
        model.addAttribute("roleMenuSections", consoleService.roleMenuSections());

        model.addAttribute("userDisplayName", "Пользователь");
        model.addAttribute("userPosition", "Должность не указана");

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }

        AppUser user = userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElse(null);

        if (user == null) {
            return;
        }

        model.addAttribute("currentUser", user);

        model.addAttribute(
                "userDisplayName",
                abbreviatedName(user.getFullName(), user.getUsername())
        );

        String specialty = user.getSpecialty();

        model.addAttribute(
                "userPosition",
                specialty == null || specialty.isBlank()
                        ? "Должность не указана"
                        : specialty
        );
    }

    /**
     * Преобразует "Иванов Иван Иванович" в "Иванов И.".
     */
    private String abbreviatedName(String fullName, String username) {
        if (fullName == null || fullName.isBlank()) {
            return username == null || username.isBlank()
                    ? "Пользователь"
                    : username;
        }

        String[] parts = fullName.trim().split("\\s+");

        if (parts.length == 1) {
            return parts[0];
        }

        return parts[0]
                + " "
                + parts[1].substring(0, 1)
                .toUpperCase(Locale.forLanguageTag("ru"))
                + ".";
    }
}