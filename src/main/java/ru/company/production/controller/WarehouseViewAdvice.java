package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.ModuleRoutes;
import ru.company.production.service.WarehouseNotificationService;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Данные текущего сотрудника склада для всех страниц модуля:
 * верхняя панель, счётчик незакрытых приходов и счётчик партий,
 * ожидающих приёмки с БВК, в боковом меню.
 *
 * Счётчики нужны на каждой странице: партия, по которой номера
 * напечатаны, но ещё не отсканирована, оставляет в реестре резерв, а
 * партия, вернувшаяся от БВК, ждёт кладовщика — и увидеть это нужно,
 * не заходя в сам раздел.
 *
 * Дополнительно отдаёт JSON-эндпоинт {@code /warehouse/notifications/counters}
 * для фоновой проверки счётчиков: клиент поллит раз в ~30 секунд,
 * сравнивает с предыдущими значениями и при появлении новых
 * уведомлений воспроизводит звук и показывает строки.
 */
@ControllerAdvice(assignableTypes = {
        WarehouseController.class,
        WarehouseReceivingController.class,
        WarehouseAcceptanceController.class,
        WarehouseZnpAcceptanceController.class,
        WarehouseQuarantineController.class,
        WarehouseSupplierController.class,
        WarehouseKittingController.class,
        SubassemblyKittingController.class,
        WarehouseStockController.class
})
@RequiredArgsConstructor
public class WarehouseViewAdvice {

    private final UserRepository userRepository;
    private final WarehouseNotificationService notificationService;

    /*
     * Счётчики считаются одним вызовом сервиса (см.
     * WarehouseNotificationService): меню получает их из модели, а
     * JSON-эндпоинт уведомлений читает тот же сервис — и второй запрос
     * за теми же числами на страницу не приходится.
     *
     * Имена атрибутов сохранены прежние: шаблоны меню уже их печатают.
     */
    @ModelAttribute
    public void addWarehouseContext(
            Authentication authentication,
            Model model
    ) {
        model.addAttribute("userDisplayName", "Пользователь");
        model.addAttribute("userPosition", "Должность не указана");

        List<WarehouseNotificationService.CounterItem> counters =
                notificationService.collectAll();

        Map<String, Long> byKey = counters.stream()
                .collect(Collectors.toMap(
                        WarehouseNotificationService.CounterItem::key,
                        WarehouseNotificationService.CounterItem::count
                ));

        /*
         * Имена атрибутов сохранены прежние — шаблоны меню уже их
         * печатают. JSON-эндпоинт для JS читает тот же сервис напрямую.
         */
        model.addAttribute("pendingReceivingCount",
                byKey.get("receiving"));
        model.addAttribute("awaitingAcceptanceCount",
                byKey.get("acceptance"));
        model.addAttribute("quarantineTransferCount",
                byKey.get("quarantine-transfer"));
        model.addAttribute("znpCellAcceptanceCount",
                byKey.get("znp-acceptance"));
        model.addAttribute("subassemblyKittingCount",
                byKey.get("subassembly-kitting"));
        model.addAttribute("kittingZnpCount",
                byKey.get("kitting-znp"));
        model.addAttribute("kittingSemiCount",
                byKey.get("kitting-semi"));

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
