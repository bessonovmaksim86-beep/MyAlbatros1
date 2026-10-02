package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.ModuleRoutes;

import java.util.Locale;

/**
 * Интерфейс исполнителей цехов.
 *
 * У исполнителей нет полного цехового модуля (он закрыт в
 * {@code SecurityConfig}), поэтому их интерфейс — минимальная
 * страница с боковым меню подразделения и единственным пунктом
 * «Мои операции», который ведёт на {@code /my-operations}.
 */
@Controller
@RequestMapping("/workshop-executor")
@RequiredArgsConstructor
public class WorkshopExecutorController {

    private final UserRepository userRepository;

    @GetMapping({"", "/"})
    public String index(Authentication authentication, Model model) {
        AppUser user = findUser(authentication);

        model.addAttribute("activePage", "my-operations");
        model.addAttribute("currentUser", user);

        if (user != null) {
            model.addAttribute(
                    "userDisplayName",
                    abbreviatedName(user.getFullName(), user.getUsername())
            );
            model.addAttribute(
                    "userPosition",
                    user.getSpecialty() == null || user.getSpecialty().isBlank()
                            ? "Должность не указана"
                            : user.getSpecialty()
            );
            model.addAttribute(
                    "moduleTitle",
                    ModuleRoutes.destinationFor(user.getRole()).name()
            );
        } else {
            model.addAttribute("userDisplayName", "Пользователь");
            model.addAttribute("userPosition", "Должность не указана");
            model.addAttribute("moduleTitle", "Цех");
        }

        return "workshop-executor/index";
    }

    private AppUser findUser(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }

        return userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElse(null);
    }

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