package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.InputControlService;
import ru.company.production.service.QualityService;

import java.util.Locale;

/**
 * Данные текущего руководителя службы качества для всех страниц
 * модуля: верхняя панель, счётчик незакрытых заказов и счётчик партий
 * с ячейками в изоляторе брака — в боковом меню.
 *
 * Счётчики нужны на каждой странице: заказ, по которому ячейки уже
 * закреплены, из разбора не исчезает, и видеть это нужно, не заходя в
 * реестр заказов. Счётчик изолятора берут у {@link InputControlService}:
 * изолятор заводят карточки входного контроля, и число партий в нём —
 * одна и та же цифра для бюро и для службы качества.
 */
@ControllerAdvice(assignableTypes = QualityController.class)
@RequiredArgsConstructor
public class QualityViewAdvice {

    private final UserRepository userRepository;
    private final QualityService qualityService;
    private final InputControlService inputControlService;

    @ModelAttribute
    public void addQualityContext(
            Authentication authentication,
            Model model
    ) {
        model.addAttribute("userDisplayName", "Пользователь");
        model.addAttribute("userPosition", "Должность не указана");

        model.addAttribute(
                "openOrderCount",
                qualityService.countOpenOrders()
        );

        model.addAttribute(
                "quarantineCount",
                inputControlService.countQuarantine()
        );

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
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