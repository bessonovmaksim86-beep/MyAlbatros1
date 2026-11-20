package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.InputControlResult;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.DeviceZnpAcceptanceService;

/**
 * Приёмка групповых ЗНП на приборы.
 *
 * Раздел находится в боковом меню склада полуфабрикатов в пункте
 * «Приборы → Приёмка групповых ЗНП». Кладовщик видит перечень
 * заказов-комплектующих (GROUP_DEVICE), у которых все приборы собраны
 * (все виды ячеек привязаны к приборам в {@code cell_instrument_bindings}),
 * и проводит приёмку приборов на склад.
 *
 * Проведение идёт redirect'ом: заказ принимают один раз, и повторный F5
 * не должен отмечать приборы второй раз.
 */
@Controller
@RequestMapping("/warehouse/devices")
@RequiredArgsConstructor
public class WarehouseDeviceZnpAcceptanceController {

    private final DeviceZnpAcceptanceService acceptanceService;
    private final UserRepository userRepository;

    /**
     * Список заказов-комплектующих с готовыми к приёмке приборами.
     */
    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("activePage", "device-acceptance");
        model.addAttribute("orders", acceptanceService.findPendingOrders());

        return "warehouse/device-acceptance/index";
    }

    /**
     * Экран приёмки заказа: его реквизиты и приборы с привязками.
     */
    @GetMapping("/{id}")
    public String acceptPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        DeviceZnpAcceptanceService.DeviceZnpAcceptanceView view =
                loadView(id, redirectAttributes);

        if (view == null) {
            return "redirect:/warehouse/devices";
        }

        model.addAttribute("activePage", "device-acceptance");
        model.addAttribute("view", view);

        return "warehouse/device-acceptance/accept";
    }

    /**
     * Проведение приёмки: приборы заказа отмечают принятыми на склад.
     */
    @PostMapping("/{id}")
    public String accept(
            @PathVariable Long id,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            AppUser user = userRepository
                    .findByUsernameIgnoreCase(authentication.getName())
                    .orElseThrow(() -> new IllegalStateException(
                            "Учётная запись сотрудника склада не найдена"
                    ));

            InputControlResult result = acceptanceService.accept(
                    id, user);

            if (result.completed()) {
                redirectAttributes.addFlashAttribute(
                        "successMessage", result.message());
            } else {
                redirectAttributes.addFlashAttribute(
                        "errorMessage", result.message());
            }
        } catch (
                jakarta.persistence.EntityNotFoundException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", exception.getMessage());
        }

        return "redirect:/warehouse/devices";
    }

    private DeviceZnpAcceptanceService.DeviceZnpAcceptanceView loadView(
            Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            return acceptanceService.loadView(id);
        } catch (
                jakarta.persistence.EntityNotFoundException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", exception.getMessage());

            return null;
        }
    }
}
