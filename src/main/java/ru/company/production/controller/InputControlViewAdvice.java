package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.InputControlService;
import ru.company.production.service.RepairKittingService;
import ru.company.production.service.WorkshopStaffService;
import ru.company.production.service.PendingDefectService;

import java.util.List;
import java.util.Locale;

/**
 * Данные текущего контролёра для всех страниц модуля: верхняя панель и
 * счётчик партий, взятых в работу, в боковом меню.
 *
 * Счётчик нужен на каждой странице: открытая карточка удерживает
 * партию от повторного взятия в работу, и видеть это контролёр должен,
 * не заходя в список партий.
 *
 * Руководителю бюро (BVK_MASTER) меню бюро дополняется руководящими
 * пунктами цехового модуля — сотрудники, распределение работ,
 * календарь (см. isBvkMaster в боковом меню). Без них переход по
 * ссылке бюро из меню руководителя терял его кнопки: на /input-control/**
 * рисовалось меню исполнителя, и возвращаться к сотрудникам и
 * распределению приходилось через адресную строку.
 *
 * Раздел «Отбраковка» адресован обоим бюро, а меню у этого адреса одно
 * — бюро входного контроля. Бюро технического контроля не имеет своих
 * реестров партий, и без признака своей роли (isBtkModule) оно получило
 * бы меню чужого бюро с пунктами, закрытыми перед его ролью: ссылка на
 * входной контроль привела бы контролёра БТК к 403. По этому признаку
 * раздел рисует цеховую панель (workshop/fragments/layout), поэтому
 * цеховые подписи — род подразделения, его наименование и границы —
 * доносит этот совет, а не WorkshopViewAdvice: он подключён к цеховым
 * контроллерам, а два совета на одном контроллере перезаписывали бы
 * currentUser в непредсказуемом порядке.
 */
@ControllerAdvice(assignableTypes = {
        InputControlController.class,
        PendingDefectsController.class
})
@RequiredArgsConstructor
public class InputControlViewAdvice {

    private final UserRepository userRepository;
    private final InputControlService inputControlService;
    private final RepairKittingService repairKittingService;
    private final WorkshopStaffService staffService;
    private final PendingDefectService pendingDefectService;

    @ModelAttribute
    public void addInputControlContext(
            Authentication authentication,
            Model model
    ) {
        model.addAttribute("userDisplayName", "Пользователь");
        model.addAttribute("userPosition", "Должность не указана");
        model.addAttribute("isBvkMaster", false);
        model.addAttribute("isBtkMaster", false);
        model.addAttribute("isBtkModule", false);

        /*
         * Роль читается до счётчиков: очередь отбраковки общая для двух
         * бюро, а реестры партий — только входного контроля. Меню
         * рисуется на каждом экране раздела, и четыре запроса за цифрами
         * по пунктам, которые роль не увидит, были бы потеряны.
         */
        AppUser user = authenticatedUser(authentication);

        boolean btkModule = user != null
                && (user.getRole() == Role.BTK_MASTER
                || user.getRole() == Role.BTK_EXECUTOR);

        model.addAttribute("isBtkModule", btkModule);

        if (!btkModule) {
            model.addAttribute(
                    "inWorkCount",
                    inputControlService.countInWork()
            );

            /*
             * Новые заявки — партии со статусом «Ожидает проверки». Их
             * держат отдельно от «в работе»: взятую в работу партию
             * контролёр знает своей, а не взятую обязан увидеть, иначе
             * заявка пролежала бы в списке до конца смены незамеченной.
             */
            model.addAttribute(
                    "awaitingControlCount",
                    inputControlService.countAwaiting()
            );

            model.addAttribute(
                    "returnCount",
                    inputControlService.countReturns()
            );

            /*
             * Счётчик изолятора — партии, а не ячейки: он отвечает на
             * вопрос «по скольким партиям в изоляторе что-то лежит», а не
             * «сколько ячеек там лежит» — второе видно в самом реестре.
             */
            model.addAttribute(
                    "quarantineCount",
                    inputControlService.countQuarantine()
            );

            /*
             * Счётчик комплектования — заказы, а не ячейки: он отвечает на
             * вопрос «сколько ЗНП на ремонт ждут комплектования из
             * изолятора», а не «сколько ячеек для них нужно».
             */
            model.addAttribute(
                    "repairKittingCount",
                    repairKittingService.countRepairKittingOrders()
            );
        }

        /*
         * Счётчик отбраковки — заявки, а не ячейки: цифра отвечает на
         * вопрос «сколько заявок бюро ещё не разобрало». Заявка без
         * решения держит номер в цеху, и видеть очередь нужно, не
         * заходя в раздел.
         *
         * Очередь общая: цех присылает заявку, а решает её то бюро, чей
         * этап контроля бракуется, — и входной, и технический. Цифра
         * поэтому считается обоим, иначе бюро не увидело бы присланных
         * заявок до перехода в раздел.
         */
        model.addAttribute(
                "pendingDefectCount",
                pendingDefectService.countPending()
        );

        if (user == null) {
            return;
        }

        model.addAttribute("currentUser", user);

        model.addAttribute(
                "userDisplayName",
                abbreviatedName(user.getFullName(), user.getUsername())
        );

        String specialty = user.getSpecialty();

        model.addAttribute(
                "userPosition",
                specialty == null || specialty.isBlank()
                        ? "Должность не указана"
                        : specialty
        );

        /*
         * Подразделение и служба читаются отдельным запросом:
         * open-in-view отключён, а граница сотрудников нужна уже
         * detached-объекту. Роль при этом перечитывается у той же
         * учётной записи — currentUser в меню обязан оставаться с
         * загруженными связями.
         */
        AppUser supervisor = userRepository
                .findWithUnitsByUsernameIgnoreCase(user.getUsername())
                .orElse(user);

        model.addAttribute("currentUser", supervisor);

        model.addAttribute(
                "unitGenitive",
                staffService.unitKind(supervisor).getGenitive()
        );

        if (btkModule) {
            addWorkshopMenuContext(model, supervisor);
        }

        /*
         * Руководящие пункты в меню включает только руководитель бюро:
         * сотрудники, распределение и календарь закрыты перед ролью
         * исполнителя, и пункт для контролёра стал бы приглашением
         * на 403.
         */
        boolean master = user.getRole() == Role.BVK_MASTER
                || user.getRole() == Role.BTK_MASTER;

        if (!master) {
            return;
        }

        model.addAttribute("isBvkMaster", user.getRole() == Role.BVK_MASTER);
        model.addAttribute("isBtkMaster", user.getRole() == Role.BTK_MASTER);

        /*
         * Пункт «Сотрудники» открыт руководителю бюро и его ролью в
         * SecurityConfig, и границей списка (WorkshopStaffService),
         * поэтому цифра берётся из той же границы, что и сам список.
         */
        List<Long> scope = staffService.unitScope(supervisor);

        model.addAttribute(
                "staffCount",
                staffService.findEmployees(scope).size()
        );
    }

    /**
     * Подписи и признаки цехового меню для бюро технического контроля.
     *
     * БТК не имеет своего интерфейса реестров: его контролёр и мастер
     * работают через «Мои операции» и распределение, то есть через
     * цеховые разделы, и в разделе «Отбраковка» им рисуют цеховую
     * панель. Цеховые атрибуты model кладёт WorkshopViewAdvice, но он
     * подключён к цеховым контроллерам, а этот адрес ведёт в модуль
     * контроля; два совета на одном контроллере перезаписывали бы
     * currentUser в непредсказуемом порядке, поэтому нужные меню
     * подписи доносит этот совет.
     */
    private void addWorkshopMenuContext(Model model, AppUser supervisor) {
        WorkshopStaffService.UnitKind unitKind =
                staffService.unitKind(supervisor);

        model.addAttribute("unitWord", unitKind.getWord());
        model.addAttribute("unitGenitive", unitKind.getGenitive());
        model.addAttribute("moduleTitle", unitKind.getModuleTitle());
        model.addAttribute("workshopName", staffService.scopeName(supervisor));
        model.addAttribute("workshopUnitId", staffService.workshopUnitId(supervisor));
        model.addAttribute(
                "fullModule",
                staffService.hasFullModule(supervisor)
        );
        model.addAttribute(
                "canViewUnit",
                staffService.canViewUnit(supervisor)
        );

        /*
         * Браковка — право цеха: заявку оформляет тот, у кого ячейка
         * лежит на столе. Бюро ячейку в работу не получает, и пункт
         * привёл бы его к форме, всегда отвечающей отказом.
         */
        model.addAttribute("canScrap", false);
        model.addAttribute("bvkModule", false);
        model.addAttribute("btkModule", true);
    }

    /**
     * Учётная запись запроса либо {@code null} для анонимного входа.
     */
    private AppUser authenticatedUser(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }

        return userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElse(null);
    }

    /**
     * Преобразует "Иванов Иван Иванович" в "Иванов И.".
     */
    private String abbreviatedName(String fullName, String username) {
        if (fullName == null || fullName.isBlank()) {
            return username == null || username.isBlank()
                    ? "Пользователь"
                    : username;
        }

        String[] parts = fullName.trim().split("\\s+");

        if (parts.length == 1) {
            return parts[0];
        }

        return parts[0]
                + " "
                + parts[1].substring(0, 1)
                .toUpperCase(Locale.forLanguageTag("ru"))
                + ".";
    }
}
