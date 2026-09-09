package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;

import java.util.Locale;

@ControllerAdvice(assignableTypes = {
        TechnologyController.class,
        TechnologyClassifierController.class
})
@RequiredArgsConstructor
public class TechnologyViewAdvice {

    private final UserRepository userRepository;

    @ModelAttribute
    public void addCurrentUser(
            Authentication authentication,
            Model model
    ) {
        setDefaultUserAttributes(model);

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication
                instanceof AnonymousAuthenticationToken) {
            return;
        }

        AppUser user = userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElse(null);

        if (user == null) {
            return;
        }

        model.addAttribute("currentUser", user);

        model.addAttribute(
                "userInitials",
                initials(
                        user.getFullName(),
                        user.getUsername()
                )
        );

        model.addAttribute(
                "userDisplayName",
                abbreviatedName(
                        user.getFullName(),
                        user.getUsername()
                )
        );

        String specialty = user.getSpecialty();

        model.addAttribute(
                "userPosition",
                specialty == null || specialty.isBlank()
                        ? "Должность не указана"
                        : specialty
        );
    }

    private void setDefaultUserAttributes(Model model) {
        model.addAttribute("userInitials", "П");
        model.addAttribute("userDisplayName", "Пользователь");
        model.addAttribute(
                "userPosition",
                "Должность не указана"
        );
    }

    private String initials(
            String fullName,
            String username
    ) {
        Locale locale = Locale.forLanguageTag("ru");

        if (fullName == null || fullName.isBlank()) {
            if (username == null || username.isBlank()) {
                return "П";
            }

            return username
                    .substring(0, 1)
                    .toUpperCase(locale);
        }

        StringBuilder result = new StringBuilder();

        for (String part : fullName.trim().split("\\s+")) {
            if (!part.isBlank()) {
                result.append(
                        part.substring(0, 1)
                                .toUpperCase(locale)
                );
            }

            if (result.length() == 2) {
                break;
            }
        }

        return result.isEmpty()
                ? "П"
                : result.toString();
    }

    /**
     * Преобразует "Иванов Иван Иванович"
     * в "Иванов И.".
     */
    private String abbreviatedName(
            String fullName,
            String username
    ) {
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