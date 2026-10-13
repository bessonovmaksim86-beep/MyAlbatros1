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
import ru.company.production.service.ModuleRoutes;
import ru.company.production.service.SubassemblyKittingService;
import ru.company.production.service.WarehouseKittingService;
import ru.company.production.service.ZnpAcceptanceService;

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
        WarehouseZnpAcceptanceController.class,
        WarehouseSupplierController.class,
        WarehouseKittingController.class,
        SubassemblyKittingController.class
})
@RequiredArgsConstructor
public class WarehouseViewAdvice {

    private final UserRepository userRepository;
    private final CellReceivingService receivingService;
    private final BvkAcceptanceService acceptanceService;
    private final ZnpAcceptanceService znpAcceptanceService;
    private final WarehouseKittingService kittingService;
    private final SubassemblyKittingService subassemblyKittingService;

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

        /*
         * Серийные ЗНП с непринятыми ячейками: приёмка ячеек стоит рядом
         * с приёмкой с БВК и тоже ждёт кладовщика — счётчик держат на
         * любой странице модуля, чтобы объём приёмки был виден сразу.
         */
        model.addAttribute(
                "znpCellAcceptanceCount",
                znpAcceptanceService.countPending()
        );

        /*
         * Счётчики комплектования: незакрытые работы склада видно из
         * любого раздела модуля, иначе кладовщик узнавал бы об объёме
         * комплектования, только открыв сам раздел.
         */
        model.addAttribute(
                "kittingZnpCount",
                kittingService.countOperations(
                        WarehouseKittingService.Section.ZNP
                )
        );

        model.addAttribute(
                "kittingSemiCount",
                kittingService.countOperations(
                        WarehouseKittingService.Section.SEMI_FINISHED
                )
        );

        /*
         * Заказы-комплектующие с операцией приёмки на склад
         * полуфабрикатов: кладовщик обязан видеть объём комплектования,
         * не заходя в сам раздел, — иначе счётчик терялся бы за
         * переходом.
         */
        model.addAttribute(
                "subassemblyKittingCount",
                subassemblyKittingService.countPending()
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

        model.addAttribute(
                "warehouseKind",
                ModuleRoutes.warehouseKind(user.getRole())
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
