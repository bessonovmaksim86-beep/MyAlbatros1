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
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.SubassemblyKittingService;

/**
 * Комплектование подсборок в ЗНП: раздел склада полуфабрикатов.
 *
 * Показывает заказы-комплектующие, в которых закреплена операция
 * приёмки на склад (тип RECEIVING, подразделение СПФ). Кладовщик
 * вносит номера ячеек, принятых на склад, — после внесения ячейка
 * считается вошедшей в состав ЗНП.
 *
 * Адреса живут внутри {@code /warehouse/**} намеренно: право на них
 * даёт уже настроенное правило модуля склада, и отдельного правила
 * в конфигурации безопасности раздел не требует.
 */
@Controller
@RequestMapping("/warehouse/subassembly-kitting")
@RequiredArgsConstructor
public class SubassemblyKittingController {

    private final SubassemblyKittingService kittingService;
    private final UserRepository userRepository;

    /**
     * Список ЗНП с операцией приёмки на склад полуфабрикатов.
     *
     * Список строится по заказам-комплектующим в работе, у которых
     * операция приёмки относится к подразделению СПФ. Кладовщик
     * выбирает заказ и переходит на его экран.
     */
    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("orders", kittingService.findOrders());
        model.addAttribute("activePage", "subassembly-kitting");

        return "warehouse/subassembly-kitting/index";
    }

    /**
     * Экран внесения ячеек: заказ и складской перечень.
     *
     * Кладовщик видит реквизиты заказа, перечень ячеек на складе
     * и уже внесённые номера. В поле вводит нужные номера и нажимает
     * «Внести в заказ».
     */
    @GetMapping("/{id}")
    public String acceptPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        SubassemblyKittingService.SubassemblyKittingView view =
                loadView(id, redirectAttributes);

        if (view == null) {
            return "redirect:/warehouse/subassembly-kitting";
        }

        model.addAttribute("activePage", "subassembly-kitting");
        model.addAttribute("view", view);

        return "warehouse/subassembly-kitting/accept";
    }

    /**
     * Проведение внесения: выбранные номера вносятся в ЗНП.
     *
     * Проведение идёт redirect'ом: ячейки вносятся один раз, и
     * повторный F5 не должен дублировать состав.
     */
    @PostMapping("/{id}")
    public String attach(
            @PathVariable Long id,
            @RequestParam String cellNumbers,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        AppUser user = userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись сотрудника склада не найдена"
                ));

        try {
            SubassemblyKittingService.KittingResult result =
                    kittingService.attachCells(
                            id, cellNumbers, user
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

        return "redirect:/warehouse/subassembly-kitting/" + id;
    }

    /** Данные экрана внесения либо {@code null} — вызывающий уходит в список. */
    private SubassemblyKittingService.SubassemblyKittingView loadView(
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
