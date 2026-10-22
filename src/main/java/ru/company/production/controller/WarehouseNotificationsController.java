package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.company.production.service.WarehouseNotificationService;

import java.util.List;

/**
 * Счётчики незакрытых дел склада в JSON — для фоновой проверки
 * уведомлений.
 *
 * Боковое меню печатает счётчики серверной отрисовкой, но кладовщик
 * сидит на одной странице и не видит, что за это время пришла новая
 * партия. Клиент (warehouse-notifications.js) опрашивает этот адрес
 * раз в ~30 секунд, сравнивает числа с сохранёнными в
 * {@code localStorage} и на приросте показывает строку вида
 * «Новое «Приёмка с БВК» — 1» с переходом в раздел.
 *
 * Адрес лежит внутри {@code /warehouse/**}: его покрывает правило
 * модуля склада, и отдельного правила в конфигурации безопасности
 * раздел не требует.
 *
 * Отдаётся только перечень с ненулевыми счётчиками ({@code /active}):
 * пустой список означает «показывать нечего», и клиенту не нужно
 * отбрасывать нули самому.
 */
@RestController
@RequestMapping("/warehouse/notifications")
@RequiredArgsConstructor
public class WarehouseNotificationsController {

    private final WarehouseNotificationService notificationService;

    /** Пункты склада, по которым есть незакрытые дела. */
    @GetMapping("/active")
    public List<WarehouseNotificationService.CounterItem> active() {
        return notificationService.collectActive();
    }
}
