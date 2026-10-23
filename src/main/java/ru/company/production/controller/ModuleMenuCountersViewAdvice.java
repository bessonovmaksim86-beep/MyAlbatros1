package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.ModuleRoutes;
import ru.company.production.service.GroupZnpDistributionService;
import ru.company.production.service.WorkshopDistributionService;
import ru.company.production.service.WorkshopStaffService;

/**
 * Счётчики разделов распределения и «Мои операции» —
 * для боковых меню всех интерфейсов.
 *
 * Оба пункта живут не в одном модуле, а сразу в нескольких: у мастеров
 * цехов, складов и бюро — в цеховом меню, у диспетчера — в меню его
 * отдела, у службы качества — в меню службы, у кладовщика и контролёра —
 * пункт «Мои операции». Держать расчёт счётчиков в каждом модуле
 * означало бы расходиться в цифрах: один отдел считал бы по горизонту
 * сервиса, другой — по размеру списка.
 *
 * Значения берёт {@link WorkshopDistributionService} — теми же
 * запросами, что и сами списки. Размер списка вместо запроса не годится:
 * меню рисуется на каждой странице, а подбор кандидатов с их загрузкой
 * ради одной цифры стоил бы десятков запросов на каждую загрузку экрана.
 *
 * Счётчик распределения проставляется только руководящей роли:
 * исполнителю раздел недоступен (закрыт в {@code SecurityConfig}), и
 * показывать ему «0» — значило бы намекать на раздел, в который его не
 * пустят. Граница — подразделение учётной записи, а не параметр запроса.
 *
 * Технологического отдела здесь нет: его меню этих разделов не содержит,
 * а {@code ControllerAdvice} выполняется для каждой страницы подключённого
 * контроллера. Оставив техбюро в списке, мы платили бы двумя запросами к
 * базе за каждый экран отдела — за цифры, которые никто не рисует.
 */
@ControllerAdvice(
        assignableTypes = {
                /* Модуль руководителя подразделения. */
                WorkshopStaffController.class,
                WorkshopCalendarController.class,
                WorkshopUnitOperationsController.class,
                WorkshopDistributionController.class,
                /* Раздел распределения групповых заказов рисует те же
                 * пункты меню, поэтому и счётчики берутся отсюда. */
                GroupZnpDistributionController.class,
                /* Интерфейсы, чьё меню содержит эти же пункты. */
                DispatcherController.class,
                DispatcherPriorityController.class,
                QualityController.class,
                WarehouseController.class,
                WarehouseReceivingController.class,
                WarehouseAcceptanceController.class,
                WarehouseSupplierController.class,
                InputControlController.class,
                /* Сам раздел «Мои операции». */
                MyOperationsController.class,
                /*
                 * Интерфейс исполнителя цеха: его меню рисует те же
                 * пункты «Мои операции» и ссылку возврата в свой модуль,
                 * поэтому brandUrl и счётчик нужны и здесь — иначе
                 * возврат в меню оборвался бы на запасной /workshop/staff,
                 * закрытый перед ролью исполнителя.
                 */
                WorkshopExecutorController.class
        }
)
@RequiredArgsConstructor
public class ModuleMenuCountersViewAdvice {

    private final UserRepository userRepository;
    private final WorkshopStaffService staffService;
    private final WorkshopDistributionService distributionService;
    private final GroupZnpDistributionService groupZnpService;

    @ModelAttribute
    public void addMenuCounters(
            Authentication authentication,
            Model model
    ) {
        /*
         * Атрибуты проставляются и до проверки аутентификации: шаблоны
         * меню обращаются к ним в th:if, и отсутствие атрибута на
         * анонимном запросе обернулось бы ошибкой Thymeleaf.
         */
        model.addAttribute("assignableTotal", 0L);
        model.addAttribute("groupZnpTotal", 0L);
        model.addAttribute("distributionTotal", 0L);
        model.addAttribute("myOperationsCount", 0L);
        model.addAttribute("myRegularOperationsCount", 0L);
        model.addAttribute("myGroupOperationsCount", 0L);
        /*
         * Цифры проведённых половин здесь не считаются: «закрыта вся
         * группа» — свойство группы «операция + дата плана», а не
         * строки плана, и счётчик повторял бы группировкой все шесть
         * границ подборa. Ставится нулём, чтобы переключатель не упал
         * на th:if над неопределённым атрибутом, и подменяется
         * MyOperationsController для той половины, что открыта.
         */
        model.addAttribute("myRegularCompletedCount", 0L);
        model.addAttribute("myGroupCompletedCount", 0L);

        /*
         * Доступность «Распределения работ» и ссылка возврата в свой
         * модуль проставляются до проверки аутентификации: меню
         * обращается к ним в th:if, и отсутствие атрибута на анонимном
         * запросе обернулось бы ошибкой Thymeleaf.
         */
        model.addAttribute("canDistribute", false);
        model.addAttribute("brandUrl", null);

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }

        /*
         * Подразделение и роль читаются у пользователя сразу:
         * open-in-view отключён, и обращение к lazy-связи у
         * detached-объекта закончилось бы
         * LazyInitializationException в меню каждой страницы.
         */
        AppUser user = userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElse(null);

        if (user == null) {
            return;
        }

        /*
         * Возврат в боковом меню ведёт в модуль роли: у кладовщика это
         * склад, у контролёра — бюро, у руководителя подразделения —
         * его сотрудники. Ссылка берётся из того же перечня, что и
         * вход в систему, чтобы меню и вход не разошлись адресами.
         */
        model.addAttribute(
                "brandUrl",
                user.getRole() == null
                        ? ModuleRoutes.WORKPLACE.url()
                        : ModuleRoutes.destinationFor(
                                user.getRole()
                        ).url()
        );

        /*
         * «Мои операции» считаются двумя половинами: обычной и по
         * групповым ЗНП. Пункт меню обязан показать весь объём
         * невыполненной работы — иначе исполнитель увидел бы в меню
         * цифру меньше, чем работ, и счёл бы групповые заказы
         * потерявшимися; проведённые строки при этом из цифры
         * исключаются (см. WorkshopDistributionService#countMyOperations).
         * Тот же расчёт держит и цифры переключателя на странице
         * раздела, поэтому считается здесь, а не в контроллере: две
         * цифры из одного запроса не расходятся, а меню и страница
         * всегда показывают один итог.
         */
        long regularOperations = distributionService.countMyOperations(user);
        long groupOperations =
                distributionService.countMyGroupOperations(user);

        model.addAttribute("myRegularOperationsCount", regularOperations);
        model.addAttribute("myGroupOperationsCount", groupOperations);
        model.addAttribute(
                "myOperationsCount",
                regularOperations + groupOperations
        );

        if (!distributionService.canDistribute(user)) {
            return;
        }

        /*
         * Раздел «Распределение работ» открыт тем же ролям, что и
         * расчёт его счётчика, — пункт в меню и право войти задаются
         * одним условием, иначе меню звало бы в 403.
         *
         * Счётчик считается по границе подразделений, а не по одному:
         * руководитель службы качества отвечает за БТК и БВК сразу, и
         * цифра по одному подразделению не сошлась бы с очередью,
         * которую он увидит в разделе.
         */
        model.addAttribute("canDistribute", true);

        long assignable =
                distributionService.countAssignable(
                        staffService.unitScope(user)
                );

        model.addAttribute("assignableTotal", assignable);

        /*
         * Счётчик групповых заказов — по той же границе подразделений.
         * Раздел открыт тем же canDistribute, поэтому и цифра считается
         * для тех же ролей: иначе пункт меню показал бы работу, в
         * которую вход закрыт.
         */
        long groupZnp = groupZnpService.countUnassigned(
                staffService.unitScope(user)
        );

        model.addAttribute("groupZnpTotal", groupZnp);

        /*
         * Пункт меню «Распределение работ» один, а очередь у него —
         * из двух половин, разделённых переключателем (обычные работы и
         * групповые ЗНП). Цифра над пунктом обязана складывать обе:
         * иначе руководитель увидел бы в меню только обычную половину и
         * счёл бы нераспределённые групповые заказы потерявшимися — ровно
         * как с «Моими операциями», где пункт тоже показывает весь объём.
         */
        model.addAttribute("distributionTotal", assignable + groupZnp);
    }
}
