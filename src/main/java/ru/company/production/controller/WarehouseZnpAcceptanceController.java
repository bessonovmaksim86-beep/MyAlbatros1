package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.InputControlResult;
import ru.company.production.dto.ZnpAcceptanceForm;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.ZnpAcceptanceService;

/**
 * Приёмка на склад ячеек, вошедших в заказ-комплектующий (ЗНП).
 *
 * Раздел стоит рядом с «Приёмкой с БВК», но принимает не партию
 * оприходования, а заказ-комплектующий: склад видит ЗНП, в состав
 * которого вошли непринятые ячейки, и проводит приёмку всех них разом.
 * Правила отбора и проведения целиком в {@link ZnpAcceptanceService},
 * контроллер только читает экран и вызывает приёмку.
 *
 * Проведение идёт redirect'ом: заказ принимают один раз, и повторный F5
 * не должен отмечать ячейки второй раз. Форма содержит поле ввода
 * номеров ячеек (по одному в строке) с чекбоксом «отметить все» —
 * аналогично БВК, чтобы кладовщик мог сверить и подтвердить состав.
 */
@Controller
@RequestMapping("/warehouse/znp-acceptance")
@RequiredArgsConstructor
public class WarehouseZnpAcceptanceController {

    private final ZnpAcceptanceService acceptanceService;
    private final UserRepository userRepository;

    /** Серийные ЗНП с непринятыми ячейками. */
    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("activePage", "znp-acceptance");
        model.addAttribute("orders", acceptanceService.findPendingOrders());

        return "warehouse/znp-acceptance/index";
    }

    /** Экран приёмки заказа: его реквизиты и непринятые ячейки. */
    @GetMapping("/{id}")
    public String acceptPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        ZnpAcceptanceService.ZnpAcceptanceView view =
                loadView(id, redirectAttributes);

        if (view == null) {
            return "redirect:/warehouse/znp-acceptance";
        }

        fillModel(model, view);

        return "warehouse/znp-acceptance/accept";
    }

    /**
     * Проведение приёмки: непринятые ячейки заказа отмечают принятыми на
     * склад, их комплектовочная операция закрывается фактом.
     */
    @PostMapping("/{id}")
    public String accept(
            @PathVariable Long id,
            @ModelAttribute("znpForm") ZnpAcceptanceForm form,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            InputControlResult result = acceptanceService.accept(
                    id,
                    form,
                    currentUser(authentication)
            );

            if (result.completed()) {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        result.message()
                );
            } else {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        result.message()
                );
            }
        } catch (
                EntityNotFoundException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/warehouse/znp-acceptance";
    }

    /** Данные экрана приёмки либо {@code null} — вызывающий уходит в список. */
    private ZnpAcceptanceService.ZnpAcceptanceView loadView(
            Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            return acceptanceService.loadView(id);
        } catch (
                EntityNotFoundException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return null;
        }
    }

    private void fillModel(
            Model model,
            ZnpAcceptanceService.ZnpAcceptanceView view
    ) {
        model.addAttribute("activePage", "znp-acceptance");
        model.addAttribute("order", view.order());
        model.addAttribute("cells", view.cells());
        model.addAttribute("cellNumbers", view.cellNumbers());
        model.addAttribute("operationName", view.operationName());
        model.addAttribute("techProcessName", view.techProcessName());
        model.addAttribute("znpForm", new ZnpAcceptanceForm());

        /*
         * Признак доступности нужен экрану так же, как списку: форма
         * проведения не должна появляться там, где приёмка ещё не
         * открылась, — иначе кладовщик жал бы кнопку, которую сервис
         * отклоняет.
         */
        model.addAttribute(
                "acceptanceAvailable",
                view.acceptanceAvailable()
        );
    }

    /**
     * Кладовщик приёмки: заказ принимают сотрудником, который его провёл,
     * — по этой строке позже сверяют остаток на складе.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись сотрудника склада не найдена"
                ));
    }
}
