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
import ru.company.production.entity.DefectConfirmed;
import ru.company.production.entity.PendingDefect;
import ru.company.production.entity.Role;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.PendingDefectService;
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

    /* Фильтр очереди: только ожидающие решения. */
    private static final String FILTER_PENDING = "pending";

    /* Фильтр очереди: принятые решения — они не исчезают из раздела. */
    private static final String FILTER_RESOLVED = "resolved";

    /* Фильтр очереди: все заявки. */
    private static final String FILTER_ALL = "all";

    private final WorkshopScrapService scrapService;
    private final PendingDefectService defectService;
    private final UserRepository userRepository;

    /**
     * Форма браковки и очередь заявок цеха.
     *
     * Параметр {@code number} подставляет номер — так работает ссылка
     * «забраковать» из карточки ячейки или из списка ЗНП.
     *
     * Параметр {@code filter} держит выбранный режим очереди: решённая
     * заявка обязана оставаться в разделе, иначе цех терял бы историю
     * своих браковок сразу после вердикта бюро и не мог бы проверить,
     * чем закончилась оформленная вчера заявка.
     */
    @GetMapping({"", "/", "/new", "/new/"})
    public String form(@RequestParam(name = "number", required = false) String number,
                       @RequestParam(name = "filter", required = false) String filter,
                       Authentication authentication,
                       Model model) {

        return openForm(newForm(number), filter, authentication, model);
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
            return openForm(scrapForm, FILTER_PENDING,
                    authentication, model);
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
    public CellScrapPreview preview(@RequestParam(name = "number", required = false)
                                    String number) {
        return scrapService.preview(number);
    }

    /**
     * Вердикт бюро по заявке цеха — прямо в цеховом разделе.
     *
     * Раздел «Забраковать» открыт мастерам цехов (правило
     * {@code /workshop/**}), а право решать по заявке — у контролёров
     * БВК и БТК (правило {@code /pending-defects/**}). Одно URL на оба
     * раздела эти границы не разводит, поэтому роль проверяется здесь:
     * иначе мастер цеха подтвердил бы брак собственной ячейки, и
     * согласование потеряло бы смысл.
     *
     * Проводка идёт redirect'ом: повторный F5 не должен провести заявку
     * второй раз.
     */
    @PostMapping("/decide")
    public String decide(@RequestParam("defectId") Long defectId,
                         @RequestParam("decision") String decision,
                         @RequestParam(name = "comment", required = false) String comment,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {

        AppUser user;

        try {
            user = currentUser(authentication);
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    e.getMessage());

            return "redirect:/workshop/scrap";
        }

        if (!canDecide(user)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Решать по заявке на отбраковку вправе только "
                            + "контролёр бюро входного или технического "
                            + "контроля"
            );

            return "redirect:/workshop/scrap";
        }

        DefectConfirmed chosen;

        try {
            chosen = DefectConfirmed.valueOf(decision);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Решение не распознано: отметьте один из двух вариантов"
            );

            return "redirect:/workshop/scrap";
        }

        try {
            PendingDefect defect = defectService.applyDefectDecision(
                    defectId,
                    chosen,
                    comment,
                    user
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    defect.getFullNumber() + ": "
                            + chosen.getDisplayName().toLowerCase()
                            + " — заявка решена"
            );
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    e.getMessage());
        }

        return "redirect:/workshop/scrap?filter=" + FILTER_RESOLVED;
    }

    /* ============================================================
       СЛУЖЕБНОЕ
       ============================================================ */

    /**
     * Право вынести вердикт по заявке — роли бюро контроля.
     *
     * Состав совпадает с {@code AccessRoles.DEFECT_REQUEST_STAFF}: те же
     * четыре роли ведут заявки в разделе входного контроля, и цеховой
     * экран не вправе расширять это право на мастеров.
     *
     * Держится здесь, а не в {@code @PreAuthorize}: правило SecurityConfig
     * уже пропустило запрос по {@code /workshop/**}, и повторить границу
     * аннотацией можно только по списку ролей в коде.
     */
    private static boolean canDecide(AppUser user) {
        Role role = user.getRole();

        return role == Role.BVK_EXECUTOR
                || role == Role.BVK_MASTER
                || role == Role.BTK_EXECUTOR
                || role == Role.BTK_MASTER;
    }

    /**
     * Форма с очередью заявок: мастер обязан видеть, что на номер
     * заявка уже есть.
     *
     * Очередь отдаётся в выбранном режиме, и признак права решать —
     * вместе с ней: форма вердикта рисуется только контролёру бюро, а
     * цех видит очередь на чтение.
     */
    private String openForm(WorkshopScrapForm form,
                            String filter,
                            Authentication authentication,
                            Model model) {

        String chosen = filter == null || filter.isBlank()
                ? FILTER_PENDING
                : filter.trim().toLowerCase();

        if (!FILTER_PENDING.equals(chosen)
                && !FILTER_RESOLVED.equals(chosen)
                && !FILTER_ALL.equals(chosen)) {
            chosen = FILTER_PENDING;
        }

        model.addAttribute("activePage", "scrap");
        model.addAttribute("scrapForm", form);
        model.addAttribute("defectReasons", scrapService.findDefectReasons());
        model.addAttribute("pending", scrapService.findPending());
        model.addAttribute("resolved", scrapService.findResolved());
        model.addAttribute("filter", chosen);
        model.addAttribute("canDecide", canDecideFor(authentication));

        return "workshop/scrap";
    }

    /**
     * Право решать по заявке для текущего пользователя — тот же состав
     * ролей, что у {@link #canDecide(AppUser)}, но без отказа: страница
     * открывается и цеху, просто без формы вердикта.
     */
    private boolean canDecideFor(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }

        return userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .map(WorkshopScrapController::canDecide)
                .orElse(false);
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
