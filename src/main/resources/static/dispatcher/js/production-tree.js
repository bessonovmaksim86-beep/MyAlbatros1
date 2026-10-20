/**
 * Раскрытие древовидного списка заказов на производство.
 *
 * Все строки рендерит сервер и порядок их задаёт тоже сервер (обход в
 * глубину), поэтому скрипту остаётся одно — скрывать и показывать
 * ветви. Сортировкой и сборкой иерархии он не занимается: дублировать
 * на клиенте то, что уже посчитано в ProductionOrderListInterceptor,
 * значило бы иметь два источника правды о дереве.
 *
 * Связь «родитель → ребёнок» читается из data-атрибутов строки по
 * НОМЕРУ заказа: проекция списка идентификатор родителя не отдаёт.
 */
(() => {
    'use strict';

    /* Строка заказа в таблице дерева. */
    const ROW = '.tree-row[data-node]';

    /*
     * Начальное состояние: свёрнуто.
     *
     * Разметка приходит с сервера без классов скрытия — иначе пустой
     * список и список без вложенных заказов выглядели бы сломанными.
     * Поэтому сразу после загрузки прячутся все строки, у которых есть
     * родитель: остаются только вершины.
     */
    function collapseAll(rows) {
        rows.forEach((row) => {
            if (row.getAttribute('data-parent')) {
                row.classList.add('row-hidden');
            }
        });
    }

    /*
     * Строки, входящие в заказ с указанным номером (непосредственные
     * дети, без рекурсии).
     */
    function childrenOf(rows, nodeNumber) {
        return rows.filter(
            (row) => row.getAttribute('data-parent') === nodeNumber
        );
    }

    /*
     * Скрытие ветви целиком.
     *
     * Обход итеративный: вложенность заказов ограничена двумя-тремя
     * уровнями, но рекурсия на произвольных данных из базы читалась бы
     * как потенциальный stack overflow.
     */
    function hideBranch(rows, nodeNumber) {
        const pending = childrenOf(rows, nodeNumber);

        while (pending.length > 0) {

            const row = pending.pop();

            row.classList.add('row-hidden');

            /* Свёрнутая ветвь обязана закрыть и свои стрелки. */
            const toggle = row.querySelector('.tree-toggle');

            if (toggle) {
                toggle.classList.remove('expanded');
            }

            pending.push(...childrenOf(rows, row.getAttribute('data-node')));
        }
    }

    /*
     * Раскрытие одной ветви: показываются только непосредственные
     * дети, их собственные ветви остаются свёрнутыми — так стрелка
     * работает ступенчато и диспетчер не теряет ориентировку.
     */
    function expandBranch(rows, nodeNumber, toggle) {
        childrenOf(rows, nodeNumber).forEach((row) => {
            row.classList.remove('row-hidden');
        });

        if (toggle) {
            toggle.classList.add('expanded');
        }
    }

    /*
     * Раскрытие дерева до последней глубины.
     *
     * Глубина здесь не нужна: достаточно показать каждую строку, у
     * которой родитель есть в списке, — а это все строки кроме
     * вершин. Оставлять «свёрнутый уровень» нельзя: родитель, показанный
     * с раскрытой стрелкой, но со скрытыми детьми, читается как ошибка.
     */
    function expandEverything(rows) {
        rows.forEach((row) => {
            row.classList.remove('row-hidden');
        });

        document.querySelectorAll('.tree-toggle').forEach((toggle) => {
            toggle.classList.add('expanded');
        });
    }

    function init() {
        const rows = Array.prototype.slice.call(
            document.querySelectorAll(ROW)
        );

        if (rows.length === 0) {
            return;
        }

        const toggleAll = document.getElementById('depthToggle');

        /*
         * Свёрнутое состояние — значение по умолчанию: список выпусков
         * с десятками вложенных ЗНП целиком нечитаем, а раскрыть его
         * одним нажатием дешевле, чем пролистывать.
         */
        collapseAll(rows);

        if (toggleAll) {
            let expanded = false;

            toggleAll.addEventListener('click', () => {

                expanded = !expanded;

                if (expanded) {
                    expandEverything(rows);
                    toggleAll.textContent = '▸ Свернуть все входящие';
                } else {
                    collapseAll(rows);
                    document.querySelectorAll('.tree-toggle').forEach(
                        (toggle) => toggle.classList.remove('expanded')
                    );
                    toggleAll.textContent = '▾ Раскрыть все входящие';
                }
            });
        }

        /*
         * Стрелки в строках: делегирование на таблицу, а не слушатель
         * на каждой кнопке — строк десятки, и вешать на каждую лишний
         * обработчик незачем.
         */
        document.addEventListener('click', (event) => {

            const toggle = event.target.closest('.tree-toggle');

            if (!toggle) {
                return;
            }

            const nodeNumber = toggle.getAttribute('data-node');

            if (toggle.classList.contains('expanded')) {
                hideBranch(rows, nodeNumber);
                toggle.classList.remove('expanded');
            } else {
                expandBranch(rows, nodeNumber, toggle);
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
