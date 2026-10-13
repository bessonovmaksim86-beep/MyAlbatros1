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
import ru.company.production.dto.ProductOperationsView;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.WorkshopDistributionService;
import ru.company.production.service.WorkshopStaffService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Руководитель подразделения: распределение работ по исполнителям.
 *
 * Раздел показывает операции заказов покупателя, запланированные
 * планировщиком на подразделение руководителя, но ещё не закреплённые
 * за людьми. Единица показа — изделие («заказ + позиция»), а внутри
 * изделия операции идут списком: мастер закрепляет работу над
 * конкретным датчиком и решает по каждой операции отдельно, поэтому
 * группового назначения на весь день или на всё изделие здесь нет.
 *
 * Структура экрана двухуровневая: список изделий, раскрытие изделия
 * показывает его операции, а в каждой операции — сотрудников
 * подразделения, способных её выполнить (с загрузкой за этот день), и
 * кнопку «Назначить». Операции получения комплектации здесь не
 * распределяются — это личные работы руководителя, они видны в
 * «Мои операции».
 *
 * Границы те же, что у остальных разделов модуля: подразделение
 * берётся из учётной записи руководителя, а не из параметров запроса,
 * поэтому мастер одного цеха не распределит работы чужого.
 */
@Controller
@RequestMapping("/workshop/distribution")
@RequiredArgsConstructor
public class WorkshopDistributionController {

    /** Выбор срока в неделях: 1, 2 (по умолчанию), 3, 4, 6, 8, 12. */
    private static final List<Integer> WEEK_CHOICES =
            List.of(1, 2, 3, 4, 6, 8, 12);

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final WorkshopDistributionService distributionService;
    private final WorkshopStaffService staffService;
    private final UserRepository userRepository;

    /**
     * Очередь распределения и уже закреплённые работы подразделения —
     * оба списка сгруппированы по изделию (заказ + позиция).
     */
    @GetMapping({"", "/"})
    public String index(
            @RequestParam(name = "weeks", required = false) Integer weeks,
            Authentication authentication,
            Model model
    ) {
        fillIndexModel(authentication, model,
                WorkshopDistributionService.normalizeWeeks(weeks));

        model.addAttribute("activePage", "distribution");

        return "workshop/distribution/index";
    }

    /**
     * Закрепление операции изделия за исполнителем.
     *
     * Форма операции отправляет пару «операция + дата плана» плюс заказ
     * и позицию: мастер закрепляет работу над конкретным датчиком, а не
     * над всей группой дня, поэтому соседние изделия той же операции
     * остаются чужими решениями.
     */
    @PostMapping("/assign")
    public String assign(
            @RequestParam Long operationId,
            @RequestParam LocalDate plannedDate,
            @RequestParam Long orderId,
            @RequestParam Integer itemPosition,
            @RequestParam Long executorId,
            @RequestParam(name = "weeks", required = false) Integer weeks,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        int horizon = WorkshopDistributionService.normalizeWeeks(weeks);

        try {
            distributionService.assignProductOperation(
                    operationId,
                    plannedDate,
                    orderId,
                    itemPosition,
                    executorId,
                    currentUser(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Операция изделия назначена исполнителю"
            );
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    message(exception, "Не удалось назначить операцию изделия")
            );
        }

        return redirect(horizon);
    }

    /**
     * Снятие назначения с операции изделия: её экземпляры возвращаются
     * в очередь распределения, другие изделия не затрагиваются.
     */
    @PostMapping("/unassign")
    public String unassign(
            @RequestParam Long operationId,
            @RequestParam LocalDate plannedDate,
            @RequestParam Long orderId,
            @RequestParam Integer itemPosition,
            @RequestParam(name = "weeks", required = false) Integer weeks,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        int horizon = WorkshopDistributionService.normalizeWeeks(weeks);

        try {
            distributionService.unassignProductOperation(
                    operationId,
                    plannedDate,
                    orderId,
                    itemPosition,
                    currentUser(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Назначение снято, операция вернулась в очередь"
            );
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    message(exception, "Не удалось снять назначение")
            );
        }

        return redirect(horizon);
    }

    private void fillIndexModel(
            Authentication authentication,
            Model model,
            int weeks
    ) {
        AppUser supervisor = currentUser(authentication);

        /*
         * Граница — список подразделений службы, а не одно: у
         * руководителя службы качества карточка подразделения пуста,
         * и запрос одного подразделения вернул бы пустой список.
         */
        var scope = staffService.unitScope(supervisor);

        List<ProductOperationsView> operations =
                distributionService.findProductOperations(
                        scope, supervisor, weeks
                );

        model.addAttribute("operations", operations);
        model.addAttribute(
                "assignedOperations",
                distributionService.findAssignedProductOperations(scope, weeks)
        );

        /*
         * Счётчика экземпляров в шапке карточки больше нет: он считал
         * сумму строк графика и расходился с числом строк таблицы, где
         * изделие — одна строка независимо от тиража. Цифра, которая
         * не сходится с тем, что мастер видит под ней, хуже отсутствия
         * цифры.
         */
        model.addAttribute(
                "workshopName",
                staffService.scopeName(supervisor)
        );

        /* Панель срока: значение, варианты и показанный период. */
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * weeks);

        Map<Integer, String> choices = new LinkedHashMap<>();
        for (Integer choice : WEEK_CHOICES) {
            choices.put(choice, weeksLabel(choice));
        }

        model.addAttribute("weeks", weeks);
        model.addAttribute("weekChoices", choices);
        model.addAttribute("periodLabel",
                "Период: " + from.format(RU_DATE)
                        + " — " + to.format(RU_DATE));
    }

    /* Срок после изменения данных: фильтр не должен сбрасываться. */
    private static String redirect(int weeks) {
        return "redirect:/workshop/distribution?weeks=" + weeks;
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
    private static String message(
            RuntimeException exception,
            String fallback
    ) {
        return exception.getMessage() == null
                || exception.getMessage().isBlank()
                ? fallback
                : exception.getMessage();
    }
}
