package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.InstrumentKittingView;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.InstrumentKittingService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Комплектация группового ЗНП на приборы.
 *
 * Раздел склада полуфабрикатов: кладовщик вносит номера ячеек,
 * принятых на склад, по каждому виду ячейки, заданному технологам
 * в контролируемых параметрах операции маршрута.
 *
 * Адреса живут внутри {@code /warehouse/**} намеренно: право на них
 * даёт уже настроенное правило модуля склада, и отдельного правила
 * в конфигурации безопасности раздел не требует.
 */
@Controller
@RequestMapping("/warehouse/instrument-kitting")
@RequiredArgsConstructor
public class InstrumentKittingController {

    private final InstrumentKittingService kittingService;
    private final UserRepository userRepository;

    /**
     * Список групповых ЗНП на приборы с операцией комплектования
     * ячеек склада.
     *
     * Кладовщик выбирает заказ и переходит на экран внесения ячеек.
     */
    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("orders", kittingService.findOrders());
        model.addAttribute("activePage", "instrument-kitting");

        return "warehouse/instrument-kitting/index";
    }

    /**
     * Экран внесения ячеек: заказ, виды ячеек, свободный склад.
     *
     * Кладовщик видит реквизиты заказа, перечень видов ячеек с их
     * укомплектованностью, свободный склад и внесённый состав.
     * Номера можно вводить вручную или сканировать штрихкодами.
     */
    @GetMapping("/{id}")
    public String acceptPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        InstrumentKittingView view =
                loadView(id, redirectAttributes);

        if (view == null) {
            return "redirect:/warehouse/instrument-kitting";
        }

        model.addAttribute("activePage", "instrument-kitting");
        model.addAttribute("view", view);

        return "warehouse/instrument-kitting/accept";
    }

    /**
     * Проведение внесения: выбранные номера вносятся в заказ по видам.
     *
     * Проведение идёт redirect'ом: ячейки вносятся один раз, и
     * повторный F5 не должен дублировать состав.
     */
    @PostMapping("/{id}")
    public String attach(
            @PathVariable Long id,
            @RequestParam Map<String, String> formParams,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        AppUser user = userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись сотрудника склада не найдена"
                ));

        /*
         * Поля формы называются cellNumbers_{classifierId};
         * отфильтровываем только их.
         */
        Map<Long, String> cellNumbersByKind = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : formParams.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("cellNumbers_") && key.length() > 12) {
                try {
                    Long kindId = Long.parseLong(key.substring(12));
                    cellNumbersByKind.put(kindId, entry.getValue());
                } catch (NumberFormatException ignored) {
                    // игнорируем нечисловые ключи
                }
            }
        }

        try {
            InstrumentKittingService.KittingResult result =
                    kittingService.attachCells(
                            id, cellNumbersByKind, user
                    );

            if (result.completed()) {
                redirectAttributes.addFlashAttribute(
                        "successMessage", result.message()
                );
            } else {
                redirectAttributes.addFlashAttribute(
                        "errorMessage", result.message()
                );
            }
        } catch (IllegalStateException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", ex.getMessage()
            );
        }

        return "redirect:/warehouse/instrument-kitting/" + id;
    }

    /** Данные экрана внесения либо {@code null} — уходят в список. */
    private InstrumentKittingView loadView(
            Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            return kittingService.loadView(id);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage", ex.getMessage()
            );

            return null;
        }
    }
}
