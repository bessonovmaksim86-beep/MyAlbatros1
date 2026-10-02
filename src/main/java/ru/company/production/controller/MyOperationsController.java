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
import ru.company.production.service.CustomerOrderSuspensionService;
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