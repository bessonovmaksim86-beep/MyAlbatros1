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
import ru.company.production.service.RepairKittingService;

import java.util.Locale;

/**
 * Данные текущего контролёра для всех страниц модуля: верхняя панель и
 * счётчик партий, взятых в работу, в боковом меню.
 *
 * Счётчик нужен на каждой странице: открытая карточка удерживает
 * партию от повторного взятия в работу, и видеть это контролёр должен,
 * не заходя в список партий.
 */
@ControllerAdvice(assignableTypes = InputControlController.class)
@RequiredArgsConstructor
public class InputControlViewAdvice {

    private final UserRepository userRepository;
    private final InputControlService inputControlService;
    private final RepairKittingService repairKittingService;

    @ModelAttribute
    public void addInputControlContext(
            Authentication authentication,
            Model model
    ) {
        model.addAttribute("userDisplayName", "Пользователь");
        model.addAttribute("userPosition", "Должность не указана");

        model.addAttribute(
                "inWorkCount",
                inputControlService.countInWork()
        );

        model.addAttribute(
                "returnCount",
                inputControlService.countReturns()
        );

        /*
         * Счётчик изолятора — партии, а не ячейки: он отвечает на
         * вопрос «по скольким партиям в изоляторе что-то лежит», а не
         * «сколько ячеек там лежит» — второе видно в самом реестре.
         */
        model.addAttribute(
                "quarantineCount",
                inputControlService.countQuarantine()
        );

        /*
         * Счётчик комплектования — заказы, а не ячейки: он отвечает на
         * вопрос «сколько ЗНП на ремонт ждут комплектования из
         * изолятора», а не «сколько ячеек для них нужно».
         */
        model.addAttribute(
                "repairKittingCount",
                repairKittingService.countRepairKittingOrders()
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