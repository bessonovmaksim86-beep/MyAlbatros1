package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.DeletionGroupKind;
import ru.company.production.dto.DeletionPreview;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.DeletionTargetKind;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.AdminDataCleanupService;
import ru.company.production.service.AdminImpersonationService;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Раздел администратора «Очистка данных».
 *
 * Раздел намеренно двухшаговый: поиск и журнал на одной странице,
 * предпросмотр — на отдельной. Жёсткое удаление заказа снимает строки
 * цехового графика, факт операций и отметки склада, и администратор обязан
 * увидеть этот состав до того, как он исчезнет, — поэтому выполнить
 * удаление можно только от формы предпросмотра, чей перечень подписан
 * сервисом (см. {@code AdminDataCleanupService#requireConfirmed}).
 *
 * Удаление и откат разнесены по двум обработчикам, а не собраны в один
 * «универсальный» по {@code kind}: у операций разные последствия (откат
 * оставляет партию черновиком, удаление стирает её), и один путь на оба
 * случая означал бы ветвление, в котором легко перепутать ветку.
 */
@Controller
@RequestMapping("/admin/cleanup")
@RequiredArgsConstructor
public class AdminDataCleanupController {

    private final AdminDataCleanupService cleanupService;
    private final UserRepository userRepository;

    /**
     * Страница очистки: поиск записей и лента уже выполненных удалений.
     *
     * Журнал показан на этой же странице, а не отдельно: вопрос «не удаляли
     * ли это уже» администратор задаёт в тот момент, когда ищет запись.
     */
    @GetMapping({"", "/"})
    public String index(
            @RequestParam(required = false) String query,
            Model model
    ) {
        model.addAttribute("activePage", "cleanup");

        model.addAttribute("query", query);
        model.addAttribute("orders", cleanupService.findOrders(query));
        model.addAttribute("batches", cleanupService.findBatches(query));

        model.addAttribute("deletions", cleanupService.history());
        model.addAttribute("deletionCount", cleanupService.historyCount());

        return "admin/cleanup";
    }

    /** Предпросмотр: что именно уйдёт вместе с записью. */
    @GetMapping("/preview/{kind}/{id}")
    public String preview(
            @PathVariable String kind,
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        model.addAttribute("activePage", "cleanup");

        DeletionTargetKind targetKind;
        DeletionPreview preview;

        try {
            targetKind = parseKind(kind);

            preview = targetKind == DeletionTargetKind.PRODUCTION_ORDER
                    ? cleanupService.previewOrder(id)
                    : cleanupService.previewBatch(id);
        } catch (
                IllegalArgumentException
                | IllegalStateException
                | EntityNotFoundException exception
        ) {
            /*
             * Причина возвращается текстом на страницу поиска: предпросмотр
             * без данных показать нечем, а безымянный отказ администратор не
             * смог бы соотнести с найденной строкой.
             */
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/admin/cleanup";
        }

        model.addAttribute("preview", preview);
        model.addAttribute("kindParam", kind);

        /*
         * Отмеченные по умолчанию группы и разрешённый для типа перечень
         * отдаёт сервис, а не шаблон: он меняется вместе с графом внешних
         * ключей, и держать его в двух местах значило бы предлагать на
         * форме то, что удаление уже не трогает.
         *
         * Заблокированные группы тоже считаются здесь: форма сравнивает с
         * ними каждую строку перечня, и вычислять набор вызовом метода из
         * шаблона означало бы пересобирать его на каждый чекбокс.
         */
        model.addAttribute(
                "selectedGroups",
                cleanupService.defaultGroups(preview)
        );

        model.addAttribute("blockedGroups", preview.blockedGroups());

        model.addAttribute(
                "allowedGroups",
                cleanupService.allowedGroups(targetKind)
        );

        return "admin/cleanup-preview";
    }

    /** Жёсткое удаление заказа на производство вместе с отмеченным составом. */
    @PostMapping("/order/{id}/delete")
    public String deleteOrder(
            @PathVariable Long id,
            @RequestParam(value = "groups", required = false) List<String> groups,
            @RequestParam(value = "confirmationToken", required = false) String token,
            @RequestParam(value = "confirmed", required = false) boolean confirmed,
            @RequestParam(value = "note", required = false) String note,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            String number = cleanupService.deleteOrder(
                    id,
                    parseGroups(groups),
                    confirmed,
                    token,
                    note,
                    actor(authentication),
                    impersonatedBy(session)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Заказ «" + number + "» удалён из базы вместе с "
                            + "отмеченным составом"
            );
        } catch (
                IllegalArgumentException
                | IllegalStateException
                | EntityNotFoundException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            /* Ошибка возвращает на предпросмотр: причина относится к составу
             * этой записи, а не к поиску, откуда администратор пришёл. */
            return "redirect:/admin/cleanup/preview/order/" + id;
        }

        return "redirect:/admin/cleanup";
    }

    /** Откат оприходования партии (или полное снятие заявки прихода). */
    @PostMapping("/batch/{id}/rollback")
    public String rollbackBatch(
            @PathVariable Long id,
            @RequestParam(value = "groups", required = false) List<String> groups,
            @RequestParam(value = "confirmationToken", required = false) String token,
            @RequestParam(value = "confirmed", required = false) boolean confirmed,
            @RequestParam(value = "note", required = false) String note,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        try {
            String label = cleanupService.rollbackBatch(
                    id,
                    parseGroups(groups),
                    confirmed,
                    token,
                    note,
                    actor(authentication),
                    impersonatedBy(session)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Оприходование «" + label + "» откачано"
            );
        } catch (
                IllegalArgumentException
                | IllegalStateException
                | EntityNotFoundException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/admin/cleanup/preview/batch/" + id;
        }

        return "redirect:/admin/cleanup";
    }

    /* ================================================================
     * Служебное
     * ================================================================ */

    /**
     * Разбор пути предпросмотра: в адресе — человекочитаемые части, а не
     * имена перечисления, чтобы ссылка оставалась объяснимой при пересылке.
     */
    private static DeletionTargetKind parseKind(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Не указан тип удаляемой записи");
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "order", "production-order" ->
                    DeletionTargetKind.PRODUCTION_ORDER;
            case "batch", "receiving-batch" -> DeletionTargetKind.RECEIVING_BATCH;
            default -> throw new IllegalArgumentException(
                    "Неизвестный тип записи для очистки"
            );
        };
    }

    /**
     * Отмеченные составы.
     *
     * Неизвестное значение отбрасывается молча: проверка на включение в
     * показанный перечень есть в сервисе, и отказ здесь лишь удвоил бы её,
     * лишив администратора внятной причины.
     */
    private static Set<DeletionGroupKind> parseGroups(List<String> groups) {

        Set<DeletionGroupKind> parsed =
                EnumSet.noneOf(DeletionGroupKind.class);

        if (groups == null) {
            return parsed;
        }

        for (String value : new LinkedHashSet<>(groups)) {
            if (value == null || value.isBlank()) {
                continue;
            }

            try {
                parsed.add(DeletionGroupKind.valueOf(value.trim()));
            } catch (IllegalArgumentException ignored) {
                /* Строка формы, которой нет в перечне, — не основание удалять всё. */
            }
        }

        return parsed;
    }

    /**
     * Исполнитель удаления.
     *
     * Берётся учётная запись, а не имя из аутентификации: журнал хранит
     * ФИО на момент операции, и удалить заказ должен тот, кто его удаляет,
     * а не обезличенный логин.
     */
    private AppUser actor(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }

        return userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElse(null);
    }

    /**
     * Логин администратора, если удаление выполнено из интерфейса другой
     * роли: ответственность несёт администратор, давший себе такие права.
     */
    private static String impersonatedBy(HttpSession session) {
        return session.getAttribute(
                AdminImpersonationService.SESSION_ADMIN
        ) instanceof String username
                ? username
                : null;
    }
}
