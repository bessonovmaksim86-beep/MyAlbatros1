package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.InputControlService;
import ru.company.production.service.WorkshopStaffService;

import java.util.List;

/**
 * Данные текущего руководителя подразделения для всех страниц модуля:
 * верхняя панель, боковое меню и граница подразделения
 * (workshopUnitId).
 *
 * Модуль общий для цехов, складов и бюро входного контроля, поэтому
 * вместе с названием подразделения в модель попадают {@code unitWord} и
 * {@code moduleTitle} — подписи страниц без них говорили бы «цех» над
 * списком склада.
 */
@ControllerAdvice(
        assignableTypes = {
                WorkshopStaffController.class,
                WorkshopCalendarController.class,
                WorkshopUnitOperationsController.class,
                WorkshopDistributionController.class,
                /*
                 * Интерфейс исполнителя цеха рисует то же боковое меню
                 * подразделения, поэтому ему нужны те же подписи рода
                 * подразделения и признак полного модуля: без них
                 * fullModule остался бы неопределён, и меню показало бы
                 * исполнителю пункты (сотрудники, операции, календарь),
                 * закрытые перед его ролью в SecurityConfig.
                 *
                 * Контроллер службы качества сюда не включён намеренно:
                 * у него собственный QualityViewAdvice, и два совета на
                 * одном контроллере перезаписывали бы currentUser и
                 * счётчики в непредсказуемом порядке.
                 */
                WorkshopExecutorController.class
        }
)
@RequiredArgsConstructor
public class WorkshopViewAdvice {

    private final UserRepository userRepository;
    private final WorkshopStaffService staffService;
    private final InputControlService inputControlService;

    @ModelAttribute
    public void addWorkshopContext(
            Authentication authentication,
            Model model
    ) {
        model.addAttribute("userDisplayName", "Пользователь");
        model.addAttribute("userPosition", "Должность не указана");

        /*
         * Подпись рода подразделения задаётся до проверки аутентификации:
         * шаблоны берут её в заголовках бокового меню, и отсутствие
         * атрибута оборвалось бы ошибкой Thymeleaf на анонимном запросе.
         */
        model.addAttribute(
                "unitWord",
                WorkshopStaffService.UnitKind.WORKSHOP.getWord()
        );
        model.addAttribute(
                "unitGenitive",
                WorkshopStaffService.UnitKind.WORKSHOP.getGenitive()
        );
        model.addAttribute(
                "moduleTitle",
                WorkshopStaffService.UnitKind.WORKSHOP.getModuleTitle()
        );

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }

        /*
         * Подразделение и служба читаются у руководителя сразу после
         * запроса: open-in-view отключён, и без EntityGraph обращение к
         * lazy-связи у detached-объекта заканчивалось бы
         * LazyInitializationException на каждой странице модуля.
         */
        AppUser supervisor = userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElse(null);

        if (supervisor == null) {
            return;
        }

        model.addAttribute("currentUser", supervisor);

        model.addAttribute(
                "userDisplayName",
                WorkshopStaffService.displayName(supervisor)
        );

        String specialty = supervisor.getSpecialty();

        model.addAttribute(
                "userPosition",
                specialty == null || specialty.isBlank()
                        ? "Должность не указана"
                        : specialty
        );

        Long unitId = staffService.workshopUnitId(supervisor);

        WorkshopStaffService.UnitKind unitKind =
                staffService.unitKind(supervisor);

        model.addAttribute("workshopUnitId", unitId);
        /*
         * Подпись границы: для мастера цеха это его подразделение, для
         * руководителя службы — наименование службы. Разница заметна
         * только у службы: её карточка учётной записи подразделение не
         * указывает, и обычное имя подразделения дало бы в заголовке
         * «Подразделение не назначено».
         */
        model.addAttribute(
                "workshopName",
                staffService.scopeName(supervisor)
        );
        model.addAttribute("unitWord", unitKind.getWord());
        model.addAttribute("unitGenitive", unitKind.getGenitive());
        model.addAttribute("moduleTitle", unitKind.getModuleTitle());
        /*
         * Полный ли это цеховой модуль: технолог, диспетчер и служба
         * качества заходят в это же меню ради распределения работ, но
         * сотрудники, операции подразделения и календарь перед их ролью
         * закрыты, и пункт в меню был бы приглашением на 403.
         */
        model.addAttribute("fullModule", staffService.hasFullModule(supervisor));
        model.addAttribute("canViewUnit", staffService.canViewUnit(supervisor));

        /*
         * Признак интерфейса службы качества: для СК цеховая обёртка
         * «ломает» визуал — бордовая палитра и шрифты конфликтуют с
         * цеховыми стилями. Когда пользователь — СК, шаблоны
         * (статьи, операции, календарь) должны рисовать quality-обёртку
         * вместо workshop-обёртки.
         */
        model.addAttribute("isSKGeneral",
                supervisor.getRole() == ru.company.production.entity.Role.SK_GENERAL
        );

        /*
         * Модуль руководителя бюро входного контроля рисует поверх
         * цехового меню пункты самого бюро (обзор, входной контроль,
         * возврат, изолятор): его подчинённые работают в /input-control,
         * и без этих ссылок руководителю пришлось бы искать работу
         * бюро в чужом адресе. Счётчики читаются только для бюро —
         * цеху они не видны, а меню рисуется на каждом экране.
         */
        boolean bvkModule =
                supervisor.getRole()
                        == ru.company.production.entity.Role.BVK_MASTER;

        model.addAttribute("bvkModule", bvkModule);

        if (bvkModule) {
            model.addAttribute(
                    "inWorkCount", inputControlService.countInWork()
            );
            model.addAttribute(
                    "returnCount", inputControlService.countReturns()
            );
            model.addAttribute(
                    "quarantineCount", inputControlService.countQuarantine()
            );
        }

        /*
         * Счётчики нужны только пунктам полного модуля. Руководителю
         * вне цеха эти пункты не показываются, и два запроса ради них
         * были бы потерянными, поэтому считать их будем только там,
         * где они попадут в меню.
         *
         * Считаются по границе подразделений, а не по одному: у
         * руководителя службы качества сотрудники и операции лежат в
         * двух подразделениях, и цифра по одному выглядела бы неверной.
         */
        boolean canViewUnit = staffService.canViewUnit(supervisor);
        List<Long> scope = canViewUnit
                ? staffService.unitScope(supervisor)
                : List.of();

        model.addAttribute("staffCount",
                canViewUnit ? staffService.findEmployees(scope).size() : 0
        );
        model.addAttribute(
                "unitOperationCount",
                canViewUnit ? staffService.countUnitOperations(scope) : 0L
        );
    }
}