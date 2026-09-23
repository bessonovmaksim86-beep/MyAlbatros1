package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.BvkAcceptanceForm;
import ru.company.production.dto.InputControlResult;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.BvkAcceptanceService;

/**
 * Приёмка на склад полуфабрикатов партии, вернувшейся из БВК.
 *
 * Раздел стоит рядом с оприходованием, но идёт после него: склад
 * принимает то, что прошло входной контроль, а ячейки, отсеянные на
 * брак, на склад не возвращаются. Поэтому список партий строится по
 * завершённым карточкам БВК, а поле номеров исключает брак —
 * правила сверки целиком в {@link BvkAcceptanceService}.
 *
 * Проведение идёт redirect'ом: партию принимают один раз, и повторный
 * F5 не должен отмечать ячейки второй раз. При отказе страница
 * возвращается тем же методом с уже введёнными номерами — перечень
 * длинный, и, потеряв его из-за одной опечатки, кладовщик вводил бы
 * его заново.
 */
@Controller
@RequestMapping("/warehouse/acceptance")
@RequiredArgsConstructor
public class WarehouseAcceptanceController {

    private final BvkAcceptanceService acceptanceService;
    private final UserRepository userRepository;

    /** Партии, вернувшиеся от БВК, с числом ячеек, годных к приёмке. */
    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("activePage", "acceptance");
        model.addAttribute("batches", acceptanceService.findBatches());

        return "warehouse/acceptance/index";
    }

    /** Экран приёмки: реквизиты партии, её годные и забракованные номера. */
    @GetMapping("/{id}")
    public String acceptPage(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        BvkAcceptanceService.AcceptanceView view =
                loadView(id, redirectAttributes);

        if (view == null) {
            return "redirect:/warehouse/acceptance";
        }

        BvkAcceptanceForm form = new BvkAcceptanceForm();

        form.setBatchId(id);

        fillAcceptModel(model, view, form);

        return "warehouse/acceptance/accept";
    }

    /**
     * Проведение приёмки: ячейки помечают принятыми на склад, партия
     * получает статус «Подсборка принята на склад».
     */
    @PostMapping("/{id}")
    public String accept(
            @PathVariable Long id,
            @Valid
            @ModelAttribute("acceptanceForm") BvkAcceptanceForm acceptanceForm,
            BindingResult errors,
            Model model,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        BvkAcceptanceService.AcceptanceView view =
                loadView(id, redirectAttributes);

        if (view == null) {
            return "redirect:/warehouse/acceptance";
        }

        if (errors.hasErrors()) {
            fillAcceptModel(model, view, acceptanceForm);
            model.addAttribute(
                    "errorMessage",
                    errors.getAllErrors().get(0).getDefaultMessage()
            );

            return "warehouse/acceptance/accept";
        }

        InputControlResult result = acceptanceService.accept(
                id,
                acceptanceForm,
                currentUser(authentication)
        );

        if (!result.completed()) {
            fillAcceptModel(model, view, acceptanceForm);
            model.addAttribute("errorMessage", result.message());

            return "warehouse/acceptance/accept";
        }

        redirectAttributes.addFlashAttribute("successMessage", result.message());

        return "redirect:/warehouse/acceptance";
    }

    /** Данные экрана приёмки либо {@code null} — вызывающий уходит в список. */
    private BvkAcceptanceService.AcceptanceView loadView(
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

    private void fillAcceptModel(
            Model model,
            BvkAcceptanceService.AcceptanceView view,
            BvkAcceptanceForm form
    ) {
        model.addAttribute("activePage", "acceptance");
        model.addAttribute("batch", view.batch());
        model.addAttribute("card", view.card());
        model.addAttribute("availableNumbers", view.availableNumbers());
        model.addAttribute("defectNumbers", view.defectNumbers());

        /*
         * Строки реестра партии по номерам: приёмку отдельных ячеек
         * видно до перепроведения, иначе кладовщик повторно отмечал бы
         * то, что уже принято.
         */
        model.addAttribute("serialMarks", view.serialsByNumber());
        model.addAttribute("acceptanceForm", form);
    }

    /**
     * Кладовщик приёмки: партию принимают сотрудником, который её
     * провёл, — по этой строке позже сверяют остаток на складе.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись сотрудника склада не найдена"
                ));
    }
}
