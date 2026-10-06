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
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.BvkAcceptanceService;
import ru.company.production.service.CellReceivingService;
import ru.company.production.service.CustomerOrderSuspensionService;
import ru.company.production.service.FinishedProductClosureService;
import ru.company.production.service.ModuleRoutes;
import ru.company.production.service.WorkshopDistributionService;
import ru.company.production.service.WorkshopStaffService;

/**
 * Работы, закреплённые за текущим пользователем.
 *
 * Адрес вынесен за пределы {@code /workshop/**} намеренно: тот путь
 * закрыт перед исполнителями, а «Мои операции» — раздел именно
 * исполнителя. Оставлять его внутри цехового модуля означало бы
 * открыть весь модуль роли, которой он не принадлежит.
 *
 * Руководителю подразделения к его собственным работам добавляются
 * операции получения комплектации: их планировщик закрепляет за
 * подразделением, а не за человеком, и в очереди распределения они
 * не участвуют — переложить их не на кого.
 *
 * Граница определяется учётной записью, а не параметром запроса:
 * пользователь видит только то, что закреплено за ним.
 */
@Controller
@RequestMapping("/my-operations")
@RequiredArgsConstructor
public class MyOperationsController {

    private final WorkshopDistributionService distributionService;
    private final WorkshopStaffService staffService;
    private final UserRepository userRepository;
    private final CustomerOrderSuspensionService suspensionService;
    private final CellReceivingService receivingService;
    private final BvkAcceptanceService acceptanceService;

    /*
     * Проведение на СГП закрывает заказы-комплектующие, все датчики
     * которых дошли до склада готовой продукции. Хук стоит здесь, а не
     * внутри сервиса распределения: проведение — событие цеха, а
     * закрытие ЗНП — событие производства, и смешивать их в одном
     * сервисе незачем.
     */
    private final FinishedProductClosureService closureService;

    /**
     * Список работ текущего пользователя.
     */
    @GetMapping({"", "/"})
    public String index(Authentication authentication, Model model) {
        AppUser user = currentUser(authentication);

        model.addAttribute(
                "operations",
                distributionService.findMyOperations(user)
        );
        model.addAttribute("userDisplayName", displayName(user));
        model.addAttribute(
                "userPosition",
                user.getSpecialty() == null || user.getSpecialty().isBlank()
                        ? "Должность не указана"
                        : user.getSpecialty()
        );
        model.addAttribute(
                "supervisor",
                distributionService.isSupervisor(user)
        );
        boolean supervisor = distributionService.isSupervisor(user);
        String ownModule =
                ModuleRoutes.destinationFor(user.getRole()).name();

        model.addAttribute("workspaceName", ownModule);
        model.addAttribute(
                "workshopName",
                supervisor
                        ? staffService.workshopName(user)
                        : ownModule
        );

        /*
         * Раздел живёт вне /workshop/**, но рисует то же боковое меню,
         * что и модуль руководителя, поэтому подписи рода подразделения
         * готовятся здесь: для руководителя — по его подразделению,
         * для исполнителя — по модулю его роли, иначе в меню появилось
         * бы «Руководитель цеха» над списком кладовщика.
         */
        WorkshopStaffService.UnitKind unitKind =
                staffService.unitKind(user);

        model.addAttribute("unitWord", unitKind.getWord());
        model.addAttribute("unitGenitive", unitKind.getGenitive());
        model.addAttribute(
                "moduleTitle",
                supervisor ? unitKind.getModuleTitle() : ownModule
        );
        model.addAttribute(
                "fullModule",
                staffService.hasFullModule(user)
        );
        /*
         * Верхняя панель общего модуля подписывает пользователя по
         * карточке (ФИО и специальность), а не по логину, поэтому
         * учётная запись передаётся в модель — иначе в шапке вместо
         * «Иванов И.» оказался бы логин учётной записи.
         */
        model.addAttribute("currentUser", user);
        model.addAttribute("activePage", "my-operations");

        /*
         * Боковое меню раздел берёт у того модуля, в который роль входит
         * по входу в систему: у кладовщика это склад, и без складских
         * подписей и счётчиков он потерял бы свой модуль — меню показалось
         * бы цеховым, а ссылка «Обзор склада» осталась бы в меню цеха.
         *
         * Счётчики читаются только для складской роли: меню рисуется на
         * каждом экране, и два запроса ради цифр, которые исполнитель
         * цеха не увидит, стоили бы замедления ему.
         */
        if (ModuleRoutes.isWarehouse(user.getRole())) {
            model.addAttribute("warehouseModule", true);
            model.addAttribute(
                    "warehouseKind",
                    ModuleRoutes.warehouseKind(user.getRole())
            );
            model.addAttribute(
                    "pendingReceivingCount",
                    receivingService.countPending()
            );
            model.addAttribute(
                    "awaitingAcceptanceCount",
                    acceptanceService.countAwaiting()
            );
        } else {
            model.addAttribute("warehouseModule", false);
        }

        return "my-operations/index";
    }

    /**
     * Проведение экземпляра операции исполнителем.
     *
     * Форма отправляет {@code rowId} — идентификатор строки графика,
     * а не группы: позиция на десять изделий закрывается десятью
     * отдельными фактами. Кто именно проводит, берётся из учётной
     * записи — параметр пользователя в запросе открыл бы возможность
     * закрыть чужую работу.
     *
     * Проведение невозможно, если заказ покупателя приостановлен
     * (миграция V46): проверка выполняется до обращения к сервису, а
     * её текст возвращается в этот же список — исполнитель видит,
     * какой заказ стоит на паузе, а не безличную ошибку.
     *
     * Ошибка возвращается тем же списком с сообщением: страница одна,
     * и уходить с неё после неудачи незачем.
     */
    @PostMapping("/complete")
    public String complete(
            @RequestParam Long rowId,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        AppUser user = currentUser(authentication);

        try {
            suspensionService.assertRowOperable(rowId);
            distributionService.completeRow(rowId, user);

            /*
             * Хук СГП выполняется после проведения: completeRow уже
             * записал дату факта, и закрытие видит актуальное состояние
             * графика. Для всех прочих подразделений вызов ничего не
             * делает.
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

        return "redirect:/my-operations";
    }

    /**
     * Фамилия и инициалы: подписывает и руководителя, и исполнителя
     * одинаково — тот же формат, что в списках подразделения.
     */
    private static String displayName(AppUser user) {
        String name = WorkshopStaffService.displayName(user);

        return name == null || name.isBlank() ? "Пользователь" : name;
    }

    /*
     * Подразделение и роль нужны для границы списка, поэтому связи
     * учётной записи загружаются вместе с ней.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись пользователя не найдена"
                ));
    }
}