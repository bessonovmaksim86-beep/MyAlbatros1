package ru.company.production.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.CellScrapPreview;
import ru.company.production.dto.WorkshopScrapForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.PendingDefect;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.WorkshopScrapService;

/**
 * Браковка ячейки/полуфабриката руководителем цеха.
 *
 * Пункт «Забраковать» ведёт в цеховом меню: мастер вводит номер
 * ячейки, номер изделия и причину брака, а сервер сам подставляет ЗНП,
 * разновидность и местоположение — заявка обязана фиксировать то, что
 * числится по матрице комплектования, а не то, что вписал человек.
 *
 * Проводка идёт redirect'ом: повторный F5 не должен оформить вторую
 * заявку на тот же номер.
 *
 * Форма не решает судьбу ячейки — она только ставит заявку в очередь
 * раздела «Отбраковка». Решение остаётся за бюро, и здесь его нет
 * намеренно.
 */
@Controller
@RequestMapping("/workshop/scrap")
@RequiredArgsConstructor
public class WorkshopScrapController {

    private final WorkshopScrapService scrapService;
    private final UserRepository userRepository;

    /**
     * Форма браковки и очередь заявок цеха.
     *
     * Параметр {@code number} подставляет номер — так работает ссылка
     * «забраковать» из карточки ячейки или из списка ЗНП.
     */
    @GetMapping({"", "/", "/new", "/new/"})
    public String form(@RequestParam(required = false) String number,
                       Model model) {

        return openForm(newForm(number), model);
    }

    /**
     * Создать заявку на браковку.
     *
     * Отказ возвращает мастеру уже введённую причину: её пишут от руки,
     * и терять из-за помехи по номеру нельзя.
     */
    @PostMapping("/create")
    public String create(@Valid @ModelAttribute("scrapForm")
                         WorkshopScrapForm scrapForm,
                         BindingResult bindingResult,
                         Authentication authentication,
                         Model model,
                         RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return openForm(scrapForm, model);
        }

        try {
            PendingDefect defect = scrapService.createScrapRequest(
                    scrapForm.getFullNumber(),
                    scrapForm.getDeviceNumber(),
                    scrapForm.getDefectReason(),
                    scrapForm.getDefectStage(),
                    currentUser(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Ячейка " + defect.getFullNumber()
                            + " забракована: заявка ожидает решения бюро"
            );
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    e.getMessage());

            return "redirect:/workshop/scrap";
        }

        return "redirect:/workshop/scrap";
    }

    /**
     * Предпросмотр ячейки при вводе номера.
     *
     * Отдаёт изделие, ЗНП и помехи, чтобы мастер увидел чужую или
     * уже ушедшую со склада ячейку до отправки заявки, а не по отказу.
     */
    @GetMapping("/preview")
    @ResponseBody
    public CellScrapPreview preview(@RequestParam(required = false)
                                    String number) {
        return scrapService.preview(number);
    }

    /* ============================================================
       СЛУЖЕБНОЕ
       ============================================================ */

    /**
     * Форма с очередью заявок: мастер обязан видеть, что на номер
     * заявка уже есть.
     */
    private String openForm(WorkshopScrapForm form, Model model) {
        model.addAttribute("activePage", "scrap");
        model.addAttribute("scrapForm", form);
        model.addAttribute("pending", scrapService.findPending());
        model.addAttribute("defectReasons", scrapService.findDefectReasons());

        return "workshop/scrap";
    }

    private static WorkshopScrapForm newForm(String number) {
        WorkshopScrapForm form = new WorkshopScrapForm();

        if (number != null && !number.isBlank()) {
            form.setFullNumber(number.trim().replaceAll("\\D", ""));
        }

        return form;
    }

    private AppUser currentUser(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("Сессия не найдена — войдите снова");
        }

        return userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись не найдена"
                ));
    }
}
