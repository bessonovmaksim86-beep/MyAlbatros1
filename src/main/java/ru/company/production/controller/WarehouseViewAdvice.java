package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.BvkAcceptanceService;
import ru.company.production.service.CellReceivingService;

import java.util.Locale;

/**
 * Данные текущего сотрудника склада для всех страниц модуля:
 * верхняя панель, счётчик незакрытых приходов и счётчик партий,
 * ожидающих приёмки с БВК, в боковом меню.
 *
 * Счётчики нужны на каждой странице: партия, по которой номера
 * напечатаны, но ещё не отсканированы, оставляет в реестре резерв, а
 * партия, вернувшаяся от БВК, ждёт кладовщика — и увидеть это нужно,
 * не заходя в сам раздел.
 */
@ControllerAdvice(assignableTypes = {
        WarehouseController.class,
        WarehouseReceivingController.class,
        WarehouseAcceptanceController.class,
        WarehouseSupplierController.class
})
@RequiredArgsConstructor
public class WarehouseViewAdvice {

    private final UserRepository userRepository;
    private final CellReceivingService receivingService;
    private final BvkAcceptanceService acceptanceService;

    @ModelAttribute
    public void addWarehouseContext(
            Authentication authentication,
            Model model
    ) {
        model.addAttribute("userDisplayName", "Пользователь");
        model.addAttribute("userPosition", "Должность не указана");

        model.addAttribute(
                "pendingReceivingCount",
                receivingService.countPending()
        );

        model.addAttribute(
                "awaitingAcceptanceCount",
                acceptanceService.countAwaiting()
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