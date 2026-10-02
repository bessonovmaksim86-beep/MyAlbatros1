(() => {
    'use strict';

    /*
     * Календарь построен таблицей, где каждая ячейка — конкретный
     * день конкретного сотрудника. Отметка задаётся двумя щелчками:
     * первый пишет дату «с», второй — дату «по». Раньше второй щелчок
     * попадал в обработчик протяжки и дату не менял, из-за чего
     * диапазон нельзя было задать без удержания кнопки мыши.
     *
     * Форма остаётся единственным источником значений: скрипт только
     * заполняет поля датами и выделяет сотрудников, поэтому календарь
     * полностью работоспособен и без JavaScript.
     */
    function parseRuDate(value) {
        const parts = /^(\d{2})\.(\d{2})\.(\d{4})$/.exec(value || '');

        if (!parts) {
            return null;
        }

        const date = new Date(
            Number(parts[3]),
            Number(parts[2]) - 1,
            Number(parts[1])
        );

        return Number.isNaN(date.getTime()) ? null : date;
    }

    function initializeCalendar() {
        const form = document.getElementById('absenceForm');

        if (!form) {
            return;
        }

        const employeeSelect = form.querySelector('[data-absence-employees]');
        const fromInput = form.querySelector('[data-absence-from]');
        const toInput = form.querySelector('[data-absence-to]');
        const cells = Array.from(document.querySelectorAll('.workshop-day'));

        if (!employeeSelect || !fromInput || !toInput || cells.length === 0) {
            return;
        }

        /*
         * Дата, по которой уже задано «с»: второй щелчок по другой дате
         * закрывает диапазон. null — значит следующий щелчок начнёт новый.
         */
        let pendingFrom = null;
        let pendingEmployee = null;
        let dragging = false;
        let dragMoved = false;

        function employeeOptions() {
            return Array.from(employeeSelect.options);
        }

        function selectedEmployees() {
            return employeeOptions()
                .filter((option) => option.selected)
                .map((option) => option.value);
        }

        function selectOnly(employeeId) {
            employeeOptions().forEach((option) => {
                option.selected = option.value === employeeId;
            });
        }

        function highlight() {
            const chosen = selectedEmployees();
            const from = parseRuDate(fromInput.value);
            const to = parseRuDate(toInput.value) || from;

            cells.forEach((cell) => {
                const date = parseRuDate(cell.dataset.date);

                const inside =
                    Boolean(from) &&
                    Boolean(to) &&
                    chosen.includes(cell.dataset.employee) &&
                    date >= from &&
                    date <= to;

                cell.classList.toggle('is-selected', inside);
            });
        }

        /*
         * Границы диапазона упорядочиваются, а не берутся в порядке
         * щелчков: «по» раньше «с» — частый случай при отметке задним
         * числом, и сервер отверг бы такую форму.
         */
        function setRange(first, second) {
            if (!first) {
                return;
            }

            if (!second) {
                fromInput.value = first;
                toInput.value = '';
                return;
            }

            const a = parseRuDate(first);
            const b = parseRuDate(second);

            fromInput.value = b && b < a ? second : first;
            toInput.value = b && b < a ? first : second;
        }

        function pick(employeeId) {
            if (!selectedEmployees().includes(employeeId)) {
                selectOnly(employeeId);
            }
        }

        function handleCell(cell) {
            const date = cell.dataset.date;

            if (!date) {
                return;
            }

            pick(cell.dataset.employee);

            const sameContext =
                pendingFrom !== null &&
                pendingEmployee === cell.dataset.employee;

            if (sameContext && date !== pendingFrom) {
                setRange(pendingFrom, date);
                pendingFrom = null;
                pendingEmployee = null;
            } else {
                pendingFrom = date;
                pendingEmployee = cell.dataset.employee;
                setRange(date, null);
            }

            highlight();
        }

        cells.forEach((cell) => {
            cell.addEventListener('mousedown', (event) => {
                event.preventDefault();

                dragging = true;
                dragMoved = false;

                handleCell(cell);
            });

            /*
             * Протяжка — то же самое, только «по» подставляется на ходу:
             * границы пересчитываются от даты первого щелчка, поэтому
             * выделять можно в обе стороны.
             */
            cell.addEventListener('mouseenter', () => {
                if (!dragging || !pendingFrom) {
                    return;
                }

                if (
                    cell.dataset.employee !== pendingEmployee ||
                    cell.dataset.date === pendingFrom
                ) {
                    return;
                }

                dragMoved = true;
                setRange(pendingFrom, cell.dataset.date);
                highlight();
            });

            cell.addEventListener('keydown', (event) => {
                if (event.key !== 'Enter' && event.key !== ' ') {
                    return;
                }

                event.preventDefault();
                handleCell(cell);
            });
        });

        document.addEventListener('mouseup', () => {
            /*
             * После протяжки диапазон закрыт, и следующий щелчок обязан
             * начать новый. Одиночный щелчок диапазон не закрывает —
             * именно тогда и ожидается второй щелчок с датой «по».
             */
            if (dragging && dragMoved) {
                pendingFrom = null;
                pendingEmployee = null;
            }

            dragging = false;
        });

        employeeSelect.addEventListener('change', highlight);

        [fromInput, toInput].forEach((input) => {
            input.addEventListener('change', () => {
                pendingFrom = null;
                pendingEmployee = null;
                highlight();
            });
        });

        /*
         * «Снять отметки» удаляет записи без возможности вернуть:
         * уточняем намерение до отправки, как и в других разделах.
         */
        const unmarkButton = form.querySelector('[data-unmark]');

        if (unmarkButton) {
            unmarkButton.addEventListener('click', (event) => {
                const names = selectedEmployees()
                    .map((id) => {
                        const option = employeeOptions().find(
                            (candidate) => candidate.value === id
                        );

                        return option ? option.textContent.trim() : id;
                    });

                /*
                 * Род подразделения подсказывает форма: без него подтверждение
                 * у руководителя склада или бюро называло бы людей
                 * «сотрудниками цеха».
                 */
                const unitGenitive =
                    form.dataset.unitGenitive || 'цеха';

                const label = names.length > 0
                    ? names.join(', ')
                    : 'всем сотрудникам ' + unitGenitive;

                if (!window.confirm(
                    'Снять отметки за выбранные даты у ' + label + '?'
                )) {
                    event.preventDefault();
                }
            });
        }

        /*
         * Без даты начала форма ушла бы на сервер с пустым fromDate и
         * получила бы ошибку валидации — подсказываем это на клиенте.
         */
        form.addEventListener('submit', (event) => {
            if (!fromInput.value.trim()) {
                event.preventDefault();
                fromInput.focus();
                window.alert('Укажите дату начала');
            }
        });

        highlight();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initializeCalendar);
    } else {
        initializeCalendar();
    }
})();