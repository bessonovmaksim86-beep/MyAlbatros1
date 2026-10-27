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
import ru.company.production.dto.AssignedOperationView;
import ru.company.production.dto.KittingZnpSerialRow;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.CustomerOrderSuspensionService;
import ru.company.production.service.FinishedProductClosureService;
import ru.company.production.service.WarehouseKittingService;
import ru.company.production.service.OperationControlParamService;
import ru.company.production.service.WorkshopDistributionService;

import java.util.List;
import java.util.Objects;

/**
 * Комплектование склада: два раздела вместо прежнего пункта
 * «Мои операции».
 *
 * Адреса живут внутри {@code /warehouse/**} намеренно: право на них
 * даёт уже настроенное правило модуля склада, и отдельного правила в
 * конфигурации безопасности разделы не требуют. Подписи модуля — род
 * склада, счётчики приёма и приёмки — кладёт {@link WarehouseViewAdvice},
 * к которому контроллер подключён, поэтому раздел не повторяет чужую
 * работу.
 *
 * Оба раздела открыты обеим ролям склада, а список строится по
 * подразделению самого раздела. Так кладовщик видит работу того
 * склада, на который смотрит: склад комплектования собирает
 * заказы-комплектующие, склад полуфабрикатов выдаёт полуфабрикаты, и
 * один экран не смешивает два маршрута.
 *
 * Граница задаётся самим адресом, а не параметром, выбирающим
 * подразделение: запросить чужой склад нельзя, потому что
 * подразделение жёстко связано с разделом в коде.
 */
@Controller
@RequestMapping("/warehouse")
@RequiredArgsConstructor
public class WarehouseKittingController {

    private final WarehouseKittingService kittingService;
    private final WorkshopDistributionService distributionService;
    private final CustomerOrderSuspensionService suspensionService;
    private final UserRepository userRepository;

    /*
     * СГП тоже проводит свою работу здесь: последний этап маршрута
     * закреплён за складом готовой продукции, и закрытие
     * заказов-комплектующих обязано наступать и с этого экрана, и с
     * «Мои операции» одинаково.
     */
    private final FinishedProductClosureService closureService;

    /*
     * Проведение склада идёт теми же строками графика, что и
     * «Мои операции», поэтому операция с контролируемыми параметрами
     * обязана вестить на форму отметки и отсюда: иначе кладовщик
     * закрыл бы работу, не внеся ни одного значения, — а граница
     * «Мои операции» для этого пути не сработала бы вовсе.
     */
    private final OperationControlParamService controlParamService;

    /**
     * Комплектование ЗНП — работы склада комплектования.
     *
     * Список разрезан «изделие → операции»: серийный номер принадлежит
     * экземпляру, а не группе операций, поэтому главной строкой идёт
     * изделие, а его операции раскрываются по клику. Операции без
     * поднятого заказа-комплектующего в список не попадают — проводить
     * их не под что.
     */
    @GetMapping({"/kitting-znp", "/kitting-znp/"})
    public String znp(Model model) {
        WarehouseKittingService.Section section =
                WarehouseKittingService.Section.ZNP;

        List<KittingZnpSerialRow> products =
                kittingService.findZnpProducts(section);

        addSectionAttributes(section, model);

        model.addAttribute("products", products);

        return section.getView();
    }

    /**
     * Комплектование полуфабрикатов — работы склада полуфабрикатов.
     *
     * Тот же иерархический разрез «изделие → операции», что и у
     * комплектования ЗНП: кладовщику нужно видеть изделия, их
     * комплектовочные операции и номер заказа-комплектующего, под
     * который поднята работа. Различаются только подразделение склада
     * и подпись заголовка — разметка и логика обобщены в
     * warehouse/kitting/fragments/znp-list.
     */
    @GetMapping({"/kitting-semi", "/kitting-semi/"})
    public String semiFinished(Model model) {
        WarehouseKittingService.Section section =
                WarehouseKittingService.Section.SEMI_FINISHED;

        List<KittingZnpSerialRow> products =
                kittingService.findZnpProducts(section);

        addSectionAttributes(section, model);

        model.addAttribute("products", products);

        return section.getView();
    }

    /**
     * Проведение экземпляра операции комплектования.
     *
     * Проводится экземпляр, а не группа: позиция на десять изделий
     * закрывается десятью фактами. Право проводить проверяет сервис
     * распределения — он требует, чтобы строка была закреплена именно
     * за проводящим, поэтому параметр запроса не даёт закрыть чужую
     * работу.
     *
     * Раздел возвращается в редиректе параметром {@code section}:
     * после проведения кладовщик обязан остаться в том же списке, а не
     * попасть на другой склад. Значение разбирается в сервис, где
     * неизвестное значение означает первый раздел, — подделать адрес
     * нельзя, потому что раздел выбирает и границу выборки, и шаблон.
     */
    @PostMapping("/kitting/complete")
    public String complete(
            @RequestParam Long rowId,
            @RequestParam(required = false) String section,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        WarehouseKittingService.Section target =
                WarehouseKittingService.ofNullable(section);

        AppUser user = userRepository
                .findWithUnitsByUsernameIgnoreCase(
                        authentication.getName()
                )
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись пользователя не найдена"
                ));

        /*
         * Операция с контролируемыми параметрами проводится только
         * через форму отметки — тот же запрет, что и в «Мои операции».
         * Раздел возвращается параметром section: кладовщик обязан
         * попасть в свой список, а не в цеховой.
         */
        if (controlParamService.hasControlParams(rowId)) {
            return "redirect:/my-operations/complete/" + rowId;
        }

        try {
            suspensionService.assertRowOperable(rowId);
            distributionService.completeRow(rowId, user);

            /*
             * Проведение на СГП закрывает дождавшиеся всех датчиков
             * заказы-комплектующие. Для склада комплектования и склада
             * полуфабрикатов вызов ничего не делает: признак
             * подразделения проверяется внутри сервиса.
             */
            closureService.onRowCompleted(rowId);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Экземпляр проведён"
            );
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage() == null
                            ? "Не удалось провести экземпляр"
                            : ex.getMessage()
            );
        }

        return "redirect:/warehouse/kitting-" + target.param();
    }

    /**
     * Привязка ячейки к заказу-комплектующему.
     *
     * Кладовщик вводит номер в поле под раскрытием операции —
     * сервис проверяет, что номер лежит на складе, не вошёл в
     * другой заказ и не превышает состав — и вносит запись в матрицу.
     * Раздел возвращается параметром section: после привязки
     * кладовщик обязан остаться в том же списке, а не попасть на
     * другой склад.
     */
    @PostMapping("/kitting/attach-cell")
    public String attachCell(
            @RequestParam Long rowId,
            @RequestParam String cellNumber,
            @RequestParam(required = false) String section,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        WarehouseKittingService.Section target =
                WarehouseKittingService.ofNullable(section);

        AppUser user = userRepository
                .findWithUnitsByUsernameIgnoreCase(
                        authentication.getName()
                )
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись пользователя не найдена"
                ));

        try {
            WarehouseKittingService.KittingResult result =
                    kittingService.attachCellToZnp(
                            rowId, cellNumber, user);

            if (result.completed()) {
                redirectAttributes.addFlashAttribute(
                        "successMessage", result.message());
            } else {
                redirectAttributes.addFlashAttribute(
                        "errorMessage", result.message());
            }
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage() == null
                            ? "Не удалось привязать ячейку"
                            : ex.getMessage()
            );
        }

        return "redirect:/warehouse/kitting-" + target.param();
    }

    /*
     * Список раздела: операции комплектования его подразделения плюс
     * подписи заголовка. Горизонт планирования и набор типов операций
     * заданы сервисом — раздел их не переопределяет, иначе два склада
     * начали бы считать комплектование по-разному.
     */
    private String section(
            WarehouseKittingService.Section section,
            Model model
    ) {
        List<AssignedOperationView> operations =
                kittingService.findOperations(section);

        addSectionAttributes(section, model);

        model.addAttribute("operations", operations);

        /*
         * Список строится поштучно, поэтому в шапке карточки считается
         * число изделий, а не групп: кладовщику важно, сколько изделий
         * собрать, а не на сколько операций они легли.
         */
        model.addAttribute("instanceCount", countInstances(operations));

        return section.getView();
    }

    /**
     * Подписи раздела: заголовок, описание и ключ активной страницы.
     *
     * Общие для обоих разрезов — плоского и «изделие → операции»:
     * различается только содержимое таблицы, а шапка обязана оставаться
     * одинаковой, иначе два склада одного маршрута выглядели бы разными
     * экранами.
     */
    private void addSectionAttributes(
            WarehouseKittingService.Section section,
            Model model
    ) {
        model.addAttribute("sectionTitle", section.getTitle());
        model.addAttribute(
                "sectionDescription",
                section.getDescription()
        );
        model.addAttribute("sectionParam", section.param());
        model.addAttribute("activePage", section.getActivePage());
    }

    /** Сколько изделий в списке — строка таблицы равна одному изделию. */
    private long countInstances(List<AssignedOperationView> operations) {
        return operations.stream()
                .map(AssignedOperationView::instances)
                .filter(Objects::nonNull)
                .mapToLong(List::size)
                .sum();
    }
}
