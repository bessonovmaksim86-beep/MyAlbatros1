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
import ru.company.production.dto.OperationDistributionView;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.WorkshopDistributionService;
import ru.company.production.service.WorkshopStaffService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 *
 * Интервал дат: periodFrom / periodTo. Если оба пустые —
 * дефолт: сегодня + 14 дней. Параметр weeks сохранён для
 * совместимости: если periodFrom / periodTo не заданы,
 * вычисляем интервал из weeks.
 */
@Controller
@RequestMapping("/workshop/distribution")
@RequiredArgsConstructor
public class WorkshopDistributionController {

    /** Срок по умолчанию, если интервал не задан: две недели. */
    private static final int DEFAULT_PERIOD_DAYS = 14;

    /*
     * Режимы переключателя раздела.
     *
     * Очередь и закреплённые работы живут на одном адресе с параметром,
     * а не на двух: это половины одного раздела, и переход между ними не
     * должен выглядеть как уход из него. Закреплённые дополнительно
     * разделяются по основанию строки — у обычного ЗНП датчик подписан
     * серийным номером выпуска, у группового номером ячейки.
     */
    private static final String QUEUE_MODE = "queue";

    /** Закреплённые работы обычного основания. */
    private static final String ASSIGNED_MODE = "assigned";

    /** Закреплённые работы группового основания. */
    private static final String ASSIGNED_GROUP_MODE = "assigned-group";

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final WorkshopDistributionService distributionService;
    private final WorkshopStaffService staffService;
    private final UserRepository userRepository;

    /**
     * Очередь распределения и уже закреплённые работы подразделения —
     * оба списка сгруппированы по изделию (заказ + позиция).
     *
     * Режим задаётся параметром {@code mode}: пустой или «regular» —
     * очередь распределения, «assigned» и «assigned-group» — закреплённые
     * работы обычного и группового основания. Разделение сделано
     * переключателем, а не двумя блоками на одном экране: закреплённая
     * работа мастеру уже не интересна в очереди — он её распределил, и
     * она только мешала читать остаток.
     */
    @GetMapping({"", "/"})
    public String index(
            @RequestParam(name = "periodFrom", required = false) String periodFrom,
            @RequestParam(name = "periodTo", required = false) String periodTo,
            @RequestParam(name = "weeks", required = false) Integer weeks,
            @RequestParam(name = "mode", required = false) String mode,
            Authentication authentication,
            Model model
    ) {
        LocalDate from = parseDate(periodFrom);
        LocalDate to = parseDate(periodTo);

        if (from == null && to == null) {
            from = LocalDate.now();
            to = from.plusDays(DEFAULT_PERIOD_DAYS);
        } else if (from == null) {
            from = to.minusDays(DEFAULT_PERIOD_DAYS);
        } else if (to == null) {
            to = from.plusDays(DEFAULT_PERIOD_DAYS);
        }

        fillIndexModel(authentication, model, from, to, viewMode(mode));

        model.addAttribute("activePage", "distribution");
        model.addAttribute("periodFrom", from != null ? from.toString() : "");
        model.addAttribute("periodTo", to != null ? to.toString() : "");

        return "workshop/distribution/index";
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Закрепление одного датчика за исполнителем.
     */
    @PostMapping("/assign")
    public String assign(
            @RequestParam Long rowId,
            @RequestParam(name = "executorId", required = false) Long executorId,
            @RequestParam(name = "periodFrom", required = false) String periodFrom,
            @RequestParam(name = "periodTo", required = false) String periodTo,
            @RequestParam(name = "mode", required = false) String mode,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        LocalDate from = parseDate(periodFrom);
        LocalDate to = parseDate(periodTo);
        if (from == null && to == null) {
            from = LocalDate.now();
            to = from.plusDays(DEFAULT_PERIOD_DAYS);
        } else if (from == null) {
            from = to.minusDays(DEFAULT_PERIOD_DAYS);
        } else if (to == null) {
            to = from.plusDays(DEFAULT_PERIOD_DAYS);
        }

        try {
            distributionService.assignRow(
                    rowId,
                    executorId,
                    currentUser(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Датчик назначен исполнителю"
            );
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    message(exception, "Не удалось назначить датчик")
            );
        }

        return redirect(from, to, mode);
    }

    /**
     * Снятие назначения с одного датчика.
     */
    @PostMapping("/unassign")
    public String unassign(
            @RequestParam Long rowId,
            @RequestParam(name = "periodFrom", required = false) String periodFrom,
            @RequestParam(name = "periodTo", required = false) String periodTo,
            @RequestParam(name = "mode", required = false) String mode,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        LocalDate from = parseDate(periodFrom);
        LocalDate to = parseDate(periodTo);
        if (from == null && to == null) {
            from = LocalDate.now();
            to = from.plusDays(DEFAULT_PERIOD_DAYS);
        } else if (from == null) {
            from = to.minusDays(DEFAULT_PERIOD_DAYS);
        } else if (to == null) {
            to = from.plusDays(DEFAULT_PERIOD_DAYS);
        }

        try {
            distributionService.unassignRow(
                    rowId,
                    currentUser(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Назначение снято, датчик вернулся в очередь"
            );
        } catch (RuntimeException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    message(exception, "Не удалось снять назначение")
            );
        }

        return redirect(from, to, mode);
    }

    /**
     * Тот же заполнитель с режимом экрана.
     *
     * Режим выбирает, какой список стоит под переключателем: очередь
     * распределения или закреплённые работы обычного и группового
     * основания. Основания разделяются намеренно — у обычного ЗНП
     * датчик подписан серийным номером выпуска, у группового номером
     * ячейки, внесённой складом, и в одной таблице эти подписи спорили
     * бы друг с другом.
     */
    private void fillIndexModel(
            Authentication authentication,
            Model model,
            LocalDate from,
            LocalDate to,
            String view
    ) {
        AppUser supervisor = currentUser(authentication);

        /*
         * Граница — список подразделений службы, а не одно: у
         * руководителя службы качества карточка подразделения пуста,
         * и запрос одного подразделения вернул бы пустой список.
         */
        var scope = staffService.unitScope(supervisor);

        /*
         * Список читается только тот, что открыт: второй заведомо пуст
         * на этом экране, и запрос ради него стоил бы двух лишних
         * выборок на каждую загрузку.
         */
        boolean queueView = QUEUE_MODE.equals(view);

        model.addAttribute("operations", queueView
                ? distributionService.findDistributionOperations(
                        scope, supervisor, from, to
                )
                : List.<OperationDistributionView>of());

        model.addAttribute(
                "assignedOperations",
                queueView
                        ? List.<OperationDistributionView>of()
                        : ASSIGNED_GROUP_MODE.equals(view)
                        ? distributionService
                                .findAssignedGroupDistributionOperations(
                                        scope, from, to
                                )
                        : distributionService
                                .findAssignedRegularDistributionOperations(
                                        scope, from, to
                                )
        );

        /*
         * Цифры всех кнопок считаются запросом по тому же интервалу:
         * кнопка обязана показать объём и того списка, который сейчас
         * не открыт, иначе переключатель не ответил бы на вопрос «есть
         * ли смысл переходить».
         */
        model.addAttribute(
                "assignedRegularCount",
                distributionService.countAssignedRegular(scope, from, to)
        );
        model.addAttribute(
                "assignedGroupCount",
                distributionService.countAssignedGroup(scope, from, to)
        );
        model.addAttribute("distributionMode", view);
        model.addAttribute("assignedView", !queueView);
        model.addAttribute("groupMode", ASSIGNED_GROUP_MODE.equals(view));

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

        /* Панель периода. */
        model.addAttribute("periodLabel",
                "Период: " + from.format(RU_DATE)
                        + " — " + to.format(RU_DATE));
    }

    /**
     * Режим экрана из параметра адреса.
     *
     * Пустое или неизвестное значение — очередь распределения: та же
     * граница по умолчанию, что была у раздела до появления закреплённых
     * половин, поэтому старая закладка ведёт к очереди, а не к пустому
     * списку.
     */
    private static String viewMode(String mode) {
        String value = mode == null ? "" : mode.trim();

        if (ASSIGNED_MODE.equalsIgnoreCase(value)) {
            return ASSIGNED_MODE;
        }

        if (ASSIGNED_GROUP_MODE.equalsIgnoreCase(value)) {
            return ASSIGNED_GROUP_MODE;
        }

        return QUEUE_MODE;
    }

    /*
     * Срок и режим после изменения данных: фильтр не должен
     * сбрасываться. Снятый датчик уходит из закреплённых в очередь, и
     * возврат на прежнее место оставил бы мастера у пустого списка,
     * пока он сам снова выберет режим.
     */
    private static String redirect(LocalDate from, LocalDate to, String mode) {
        String view = mode == null || mode.isBlank() ? QUEUE_MODE : mode.trim();

        return "redirect:/workshop/distribution"
                + "?periodFrom=" + from
                + "&periodTo=" + to
                + "&mode=" + view;
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
