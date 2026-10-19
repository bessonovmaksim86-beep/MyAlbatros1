package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.GroupZnpView;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.GroupZnpDistributionService;
import ru.company.production.service.WorkshopStaffService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Руководитель подразделения: распределение работ групповых заказов.
 *
 * Раздел показывает групповые ЗНП (V38), чей план принадлежит
 * подразделению руководителя. Единица показа — заказ, раскрытие
 * показывает внесённые складом ячейки, раскрытие ячейки — операции
 * маршрута этой ячейки с исполнителями и подбором людей.
 *
 * Назначение идёт по списку {@code rowId}: одна и та же форма
 * обслуживает и закрепление одной операции одной ячейки, и пакетное
 * назначение по чекбоксу «выделить все», когда всю операцию ведёт один
 * сотрудник. Сервер закрепляет ровно отмеченное, поэтому снятая
 * отметка исключает ячейку из назначения, а не перекладывает её на
 * другого человека.
 *
 * Граница та же, что у общего распределения: подразделение берётся из
 * учётной записи руководителя, а не из параметров запроса, поэтому
 * мастер одного цеха не распределит работу чужого.
 */
@Controller
@RequestMapping("/workshop/distribution/group-znp")
@RequiredArgsConstructor
public class GroupZnpDistributionController {

    /** Выбор срока в неделях: тот же ряд, что у общего распределения. */
    private static final List<Integer> WEEK_CHOICES =
            List.of(1, 2, 3, 4, 6, 8, 12);

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final GroupZnpDistributionService groupZnpService;
    private final WorkshopStaffService staffService;
    private final UserRepository userRepository;

    /**
     * Групповые заказы подразделения с их ячейками и работами.
     */
    @GetMapping({"", "/"})
    public String index(
            @RequestParam(name = "weeks", required = false) Integer weeks,
            Authentication authentication,
            Model model
    ) {
        int horizon = GroupZnpDistributionService.normalizeWeeks(weeks);

        AppUser supervisor = currentUser(authentication);
        var scope = staffService.unitScope(supervisor);

        List<GroupZnpView> orders =
                groupZnpService.findGroupZnp(scope, horizon);

        model.addAttribute("orders", orders);
        model.addAttribute("workshopName", staffService.scopeName(supervisor));
        model.addAttribute("weeks", horizon);
        model.addAttribute("weekChoices", weekChoices());
        model.addAttribute("periodLabel", periodLabel(horizon));
        model.addAttribute(
                "workshopUnitId",
                staffService.workshopUnitId(supervisor)
        );

        model.addAttribute("activePage", "group-znp");

        return "workshop/distribution/group-znp";
    }

    /**
     * Закрепление отмеченных работ за исполнителем.
     *
     * Форма отдаёт список {@code rowId} — по одной строке на каждую
     * отмеченную ячейку операции. Отмеченная «выделить все» форма
     * присылает все строки операции, и мастер закрепляет операцию
     * одним решением.
     */
    @PostMapping("/assign")
    public String assign(
            @RequestParam(name = "rowId") List<Long> rowIds,
            // Параметр необязателен намеренно: форма без радиовыбора
            // иначе отвергается страницей ошибки, а понятный ответ про
            // выбор исполнителя формирует сервис.
            @RequestParam(name = "executorId", required = false) Long executorId,
            @RequestParam(name = "weeks", required = false) Integer weeks,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        int horizon = GroupZnpDistributionService.normalizeWeeks(weeks);

        try {
            groupZnpService.assignRows(
                    rowIds, executorId, currentUser(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Работы группового заказа назначены исполнителю"
            );
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    message(exception,
                            "Не удалось назначить работы группового заказа")
            );
        }

        return redirect(horizon);
    }

    /**
     * Снятие назначения с отмеченных работ: они возвращаются в
     * очередь, соседние ячейки той же операции не затрагиваются.
     */
    @PostMapping("/unassign")
    public String unassign(
            @RequestParam(name = "rowId") List<Long> rowIds,
            @RequestParam(name = "weeks", required = false) Integer weeks,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        int horizon = GroupZnpDistributionService.normalizeWeeks(weeks);

        try {
            groupZnpService.unassignRows(
                    rowIds, currentUser(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Назначение снято, работы вернулись в очередь"
            );
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    message(exception, "Не удалось снять назначение")
            );
        }

        return redirect(horizon);
    }

    /* Панель срока: значение, варианты и показанный период. */
    private static Map<Integer, String> weekChoices() {
        Map<Integer, String> choices = new LinkedHashMap<>();

        for (Integer choice : WEEK_CHOICES) {
            choices.put(choice, weeksLabel(choice));
        }

        return choices;
    }

    private static String periodLabel(int weeks) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * weeks);

        return "Период: " + from.format(RU_DATE)
                + " — " + to.format(RU_DATE);
    }

    /* Срок после изменения данных: фильтр не должен сбрасываться. */
    private static String redirect(int weeks) {
        return "redirect:/workshop/distribution/group-znp?weeks=" + weeks;
    }

    /** Подпись варианта срока: «1 неделя», «2 недели», «5 недель». */
    private static String weeksLabel(int weeks) {
        int mod10 = weeks % 10;
        int mod100 = weeks % 100;

        String noun;

        if (mod10 == 1 && mod100 != 11) {
            noun = "неделя";
        } else if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            noun = "недели";
        } else {
            noun = "недель";
        }

        return weeks + " " + noun;
    }

    /*
     * Подразделение руководителя определяет границу распределения,
     * поэтому связи учётной записи загружаются вместе с ней.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись руководителя не найдена"
                ));
    }

    /**
     * Сообщение об ошибке: у проверок сервиса текст всегда есть, но
     * без него страница осталась бы с пустым алертом, и мастер не
     * понял бы, что ему исправить.
     */
    private static String message(RuntimeException exception, String fallback) {
        return exception.getMessage() == null
                || exception.getMessage().isBlank()
                ? fallback
                : exception.getMessage();
    }
}
