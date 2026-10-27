package ru.company.production.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.PendingDefectForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.DefectConfirmed;
import ru.company.production.entity.PendingDefect;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.PendingDefectService;

/**
 * Отбраковка ячейки/подсборки: заявки цеха и решения бюро.
 *
 * Раздел открыт ролям бюро входного и технического контроля: заявку
 * создаёт цех, а решает её тот, кто отвечает за качество. Разделение
 * по ролям здесь только в счётчике меню — очередь видна всем.
 *
 * Сохранение и решение идут redirect'ом: повторный F5 не должен
 * провести заявку второй раз и задвоить событие в журнале перемещений.
 *
 * Реквизиты ячейки (разновидность, изделие, партия, местоположение)
 * страница не передаёт — их берёт сервис по номеру. Форма решения
 * несёт ровно два поля: верит ли контролёр заявке цеха, и что он
 * пишет об этом в паспорт ячейки.
 */
@Controller
@RequestMapping("/pending-defects")
@RequiredArgsConstructor
public class PendingDefectsController {

    /** Режим реестра: очередь согласования. */
    private static final String TAB_PENDING = "pending";

    /** Режим реестра: принятые решения. */
    private static final String TAB_RESOLVED = "resolved";

    private final PendingDefectService pendingDefectService;
    private final UserRepository userRepository;

    /**
     * Форма отбраковки: ввод номера и причины.
     *
     * Параметр number позволяет открыть форму уже с подставленным
     * номером — так работает ссылка «отбраковать» из перечня ячейки.
     */
    @GetMapping({"/new", "/new/"})
    public String newForm(@RequestParam(required = false) String number,
                          Model model) {

        model.addAttribute("activePage", "defects");
        model.addAttribute("tab", TAB_PENDING);

        PendingDefectForm form = new PendingDefectForm();

        if (number != null && !number.isBlank()) {
            form.setFullNumber(number.trim());
        }

        model.addAttribute("pendingDefectForm", form);

        return "input-control/pending-defects/index";
    }

    /**
     * Реестр заявок: очередь согласования и принятые решения.
     */
    @GetMapping({"", "/", "/list", "/list/"})
    public String list(@RequestParam(required = false) String tab,
                       Model model) {

        model.addAttribute("activePage", "defects");
        model.addAttribute("tab", TAB_RESOLVED.equals(tab)
                ? TAB_RESOLVED
                : TAB_PENDING);
        model.addAttribute("pending", pendingDefectService.findPending());
        model.addAttribute("resolved", pendingDefectService.findResolved());
        model.addAttribute("pendingDefectForm", new PendingDefectForm());

        return "input-control/pending-defects/index";
    }

    /**
     * Создать заявку на отбраковку.
     *
     * Отказ сервиса возвращает оператору уже введённую причину: заявка
     * пишется от руки, и терять её из-за номера, который уже в очереди,
     * нельзя.
     */
    @PostMapping("/create")
    public String create(@Valid @ModelAttribute("pendingDefectForm")
                         PendingDefectForm pendingDefectForm,
                         BindingResult bindingResult,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return reopenForm(pendingDefectForm, bindingResult);
        }

        try {
            PendingDefect defect = pendingDefectService.create(
                    pendingDefectForm, currentUser(authentication));

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Заявка на отбраковку " + defect.getCellKindLabel()
                            + " " + defect.getFullNumber()
                            + " создана и ожидает решения бюро"
            );
        } catch (IllegalStateException e) {
            bindingResult.rejectValue(
                    "fullNumber",
                    "pendingDefect.invalid",
                    e.getMessage()
            );

            return reopenForm(pendingDefectForm, bindingResult);
        }

        return "redirect:/pending-defects/list?tab=" + TAB_PENDING;
    }

    /**
     * Решение по заявке: подтверждён дефект или не подтверждён.
     *
     * Оба варианта ведут ячейку в изолятор брака — различие только в
     * подписи для анализа брака, поэтому направление сервер не спрашивает.
     * Комментарий обязателен по смыслу раздела, но не по базе: паспорт
     * обязан объяснить, почему ячейку признали годной или нет.
     */
    @PostMapping("/decide")
    public String decide(@RequestParam Long defectId,
                         @RequestParam String decision,
                         @RequestParam(required = false) String comment,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {

        DefectConfirmed chosen;

        try {
            chosen = DefectConfirmed.valueOf(decision);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Решение не распознано: отметьте один из двух вариантов"
            );

            return "redirect:/pending-defects/list?tab=" + TAB_PENDING;
        }

        try {
            PendingDefect defect = pendingDefectService.applyDefectDecision(
                    defectId,
                    chosen,
                    comment,
                    currentUser(authentication));

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    defect.getFullNumber() + ": "
                            + chosen.getDisplayName().toLowerCase()
                            + ", ячейка передана складу изолятора"
            );
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    e.getMessage());
        }

        return "redirect:/pending-defects/list?tab=" + TAB_PENDING;
    }

    /* ============================================================
       СЛУЖЕБНОЕ
       ============================================================ */

    /**
     * Возврат к реестру с незакрытой формой.
     *
     * Реестр остаётся на экране: оператор видит очередь и понимает,
     * что именно удержало заявку, — отдельная страница ошибки потеряла
     * бы этот контекст.
     */
    private String reopenForm(PendingDefectForm form,
                              BindingResult bindingResult) {

        form.setFullNumber(digitsOnly(form.getFullNumber()));

        if (bindingResult.hasFieldErrors("fullNumber")) {
            return "input-control/pending-defects/index";
        }

        return "input-control/pending-defects/index";
    }

    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись не найдена"
                ));
    }

    private static String digitsOnly(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }
}
