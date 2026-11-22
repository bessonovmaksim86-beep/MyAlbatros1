package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.VoiceCommandService;

/**
 * Голосовая команда перехода («открой у меня …»).
 *
 * Принимает уже нормализованный термин (слово-команду и произнесённые
 * числа разбирает клиент в js/voice-search.js) и отвечает, в какой
 * раздел вести пользователя. Разбор номера заказа и проверка прав —
 * за {@link VoiceCommandService}.
 *
 * Нормализация намеренно не дублируется здесь: превращение «зэ эн пэ
 * сто двадцать три» в «ЗНП 123» живёт в одном месте — на клиенте.
 * Контроллер получает готовый поисковый термин и решает только
 * вопрос доступа, поэтому расхождение между голосовом и текстовым
 * поиском невозможно по построению.
 *
 * Доступ к /voice/** закрыт anyRequest().authenticated() в
 * SecurityConfig; роль берётся из SecurityContext, и при входе
 * администратора в чужой интерфейс она уже принадлежит тому
 * интерфейсу, в котором работает человек.
 */
@RestController
@RequestMapping("/voice")
@RequiredArgsConstructor
public class VoiceCommandController {

    private final VoiceCommandService voiceCommandService;
    private final UserRepository userRepository;

    /**
     * Метод GET, а не POST: команда только читает и ничего не меняет,
     * а POST уперся бы в CSRF-защиту Spring Security, токен которой у
     * фонового fetch нет. Безопасный метод CSRF не блокирует, и для
     * операции без побочных эффектов это корректно, а не обход защиты.
     *
     * @param query   нормализованный термин без слова-команды
     * @param section раздел, на странице которого стоит пользователь;
     *                определяет, в какой из нескольких подходящих
     *                разделов вести найденный объект. Берётся со
     *                страницы, а не из реферера: реферер браузеры
     *                срезают, а угадать модуль по адресу запроса
     *                нельзя — /voice/** общий для всех разделов.
     * @return адрес раздела для перехода либо признак отката на поиск
     */
    @GetMapping("/command")
    public ResponseEntity<VoiceCommandResponse> handleCommand(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "section", required = false) String section
    ) {

        AppUser user = resolveUser();

        /*
         * Без учётной записи роль неизвестна, а без роли нельзя
         * проверить доступ: откатываем на обычный поиск, который
         * сам разберётся с правами на выдаче.
         */
        if (user == null) {
            return ResponseEntity.ok(VoiceCommandResponse.fallback(query));
        }

        VoiceCommandService.VoiceCommand command =
                voiceCommandService.handle(query, user.getRole(), section);

        /*
         * Раздел открыт роли — ведём в него. Иначе (объект не найден
         * или прав нет) клиент продолжит обычный поиск по тому же
         * термину: команда не должна терять находку только потому,
         * что перейти в раздел нельзя.
         */
        if (command.canRedirect()) {
            return ResponseEntity.ok(
                    new VoiceCommandResponse(command.url(), null, false)
            );
        }

        return ResponseEntity.ok(
                VoiceCommandResponse.fallback(command.query())
        );
    }

    private AppUser resolveUser() {
        Authentication auth =
                SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || auth.getName() == null) {
            return null;
        }

        return userRepository
                .findByUsernameIgnoreCase(auth.getName())
                .orElse(null);
    }

    /**
     * Ответ клиенту.
     *
     * @param url    адрес раздела; заполнён только когда в него можно
     * @param query  термин для обычного поиска (при откате)
     * @param fallback true — вести не в раздел, а в поиск
     */
    public record VoiceCommandResponse(
            String url,
            String query,
            boolean fallback
    ) {

        static VoiceCommandResponse fallback(String query) {
            return new VoiceCommandResponse(null, query, true);
        }
    }
}
