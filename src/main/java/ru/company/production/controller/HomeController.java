package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.ModuleRoutes;

import java.util.Locale;

/**
 * Рабочий стол роли, у которой собственного модуля пока нет.
 *
 * Страница существует не для удобства: {@code ModuleRoutes#WORKSPACE} ведёт
 * на неё все роли вне диспетчерского, технологического отделов и цеха, а
 * администратор входит в них именно через подмену. Без этой страницы вход
 * под такую роль заканчивался бы ошибкой 404, а вместе с ней терялась и
 * плашка возврата в админку.
 */
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final UserRepository userRepository;

    @GetMapping({"/home", "/home/"})
    public String home(Authentication authentication, Model model) {
        AppUser user = findUser(authentication);

        model.addAttribute("activePage", "home");
        model.addAttribute("currentUser", user);
        model.addAttribute(
                "userDisplayName",
                user == null
                        ? "Пользователь"
                        : abbreviatedName(
                                user.getFullName(),
                                user.getUsername()
                        )
        );
        model.addAttribute(
                "userPosition",
                user == null || user.getSpecialty() == null
                        || user.getSpecialty().isBlank()
                        ? "Должность не указана"
                        : user.getSpecialty()
        );

        /*
         * Название раздела берётся у того же справочника, что и адрес входа,
         * чтобы рабочий стол не расходился с маршрутизацией после входа.
         */
        model.addAttribute(
                "workspaceName",
                user == null
                        ? ModuleRoutes.WORKPLACE.name()
                        : ModuleRoutes.destinationFor(user.getRole()).name()
        );
        model.addAttribute(
                "userRoleName",
                user == null ? null : user.getRole().getDisplayName()
        );

        return "home";
    }

    /**
     * Подразделение и служба читаются EntityGraph-запросом: open-in-view
     * отключён, а карточка сотрудника показывает их оба.
     */
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