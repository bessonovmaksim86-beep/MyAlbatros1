(() => {
    'use strict';

    /*
     * Приёмка на склад подсборки, вернувшейся из БВК.
     *
     * Поле номеров живёт по тем же правилам, что выборка в карточке
     * БВК: сканер печатает номер и жмёт Enter, разделители — перевод
     * строки, запятая и точка с запятой. Номера разбираются здесь так
     * же, как их разбирает сервис, — иначе счётчик расходился бы с
     * тем, что реально уйдёт на проверку.
     *
     * Проведение по отметке «отметить все номера ячеек» необратимо:
     * состав партии подставляет сервер, и после приёмки его не
     * изменить. Поэтому форма не уходит на сервер, пока кладовщик не
     * подтвердит состав в диалоге.
     */
    const FULL_NUMBER_LENGTH = 9;

    const FORM = document.getElementById('acceptanceForm');

    if (!FORM) {
        return;
    }

    const ALL_CELLS = document.getElementById('allCells');
    const NUMBERS_TEXT = document.getElementById('cellNumbersText');
    const COUNTER = document.getElementById('acceptanceCounter');
    const ALL_HINT = document.getElementById('allCellsHint');
    const SUBMIT = document.getElementById('acceptButton');

    const DIALOG = document.getElementById('acceptDialog');
    const DIALOG_COUNT = document.getElementById('acceptDialogCount');
    const DIALOG_CONFIRM = document.getElementById('acceptDialogConfirm');
    const DIALOG_CANCEL = document.getElementById('acceptDialogCancel');

    const available = splitNumbers(FORM.getAttribute('data-available'));

    const defects = new Set(
        Array.from(
            document.querySelectorAll('#defectNumbers [data-number]'),
            (item) => item.dataset.number
        )
    );

    const numberItems = Array.from(
        document.querySelectorAll('#availableNumbers [data-number]')
    );

    /* Принятые сервером ячейки подсвечены заранее — их не перекрашиваем. */
    const alreadyAccepted = new Set(
        numberItems
            .filter((item) => item.classList.contains('is-accepted'))
            .map((item) => item.dataset.number)
    );

    function splitNumbers(value) {
        if (!value) {
            return [];
        }

        return value
            .split(',')
            .map((item) => item.trim())
            .filter((item) => item.length > 0);
    }

    /* Те же разделители и та же длина номера, что в BvkAcceptanceService. */
    function readNumbers() {
        if (!NUMBERS_TEXT || !NUMBERS_TEXT.value) {
            return [];
        }

        const seen = new Set();

        NUMBERS_TEXT.value
            .split(/[,;\r\n]+/)
            .forEach((line) => {
                const digits = line.replace(/\D/g, '');

                if (digits.length === FULL_NUMBER_LENGTH) {
                    seen.add(digits);
                }
            });

        return Array.from(seen);
    }

    function chosenNumbers() {
        return ALL_CELLS && ALL_CELLS.checked ? available : readNumbers();
    }

    function render() {
        const allSelected = Boolean(ALL_CELLS && ALL_CELLS.checked);
        const chosen = new Set(chosenNumbers());

        if (NUMBERS_TEXT) {
            /*
             * Под отметкой поле закрыто от правки: сервер подставляет
             * полный перечень годных ячеек, и введённое поверх него
             * всё равно не участвовало бы в проведении.
             */
            NUMBERS_TEXT.readOnly = allSelected;
        }

        if (ALL_HINT) {
            ALL_HINT.hidden = !allSelected;
        }

        numberItems.forEach((item) => {
            const accepted = alreadyAccepted.has(item.dataset.number);

            item.classList.toggle(
                'is-chosen',
                chosen.has(item.dataset.number) && !accepted
            );
        });

        if (COUNTER) {
            const matched = available.filter(
                (number) => chosen.has(number)
            ).length;

            COUNTER.textContent = allSelected
                ? available.length + ' из ' + available.length + ' (все)'
                : matched + ' из ' + available.length;

            COUNTER.classList.toggle(
                'is-complete',
                available.length > 0 && matched === available.length
            );

            COUNTER.classList.toggle(
                'is-danger',
                readNumbers().some((number) => defects.has(number))
            );
        }
    }

    /* =========================================================
       ПОДТВЕРЖДЕНИЕ ПРОВЕДЕНИЯ
       ========================================================= */

    let confirmed = false;

    function openDialog() {
        if (DIALOG_COUNT) {
            DIALOG_COUNT.textContent = String(available.length);
        }

        if (DIALOG) {
            DIALOG.hidden = false;

            if (DIALOG_CONFIRM) {
                DIALOG_CONFIRM.focus();
            }
        }
    }

    function closeDialog() {
        if (DIALOG) {
            DIALOG.hidden = true;

            if (SUBMIT) {
                SUBMIT.focus();
            }
        }
    }

    FORM.addEventListener('submit', (event) => {
        /*
         * Проведение по отметке «все номера» запрашивают отдельно:
         * ошибочный состав партии после приёмки не исправить, поэтому
         * первый запуск формы только показывает подтверждение.
         */
        if (confirmed || !ALL_CELLS || !ALL_CELLS.checked || !DIALOG) {
            return;
        }

        event.preventDefault();
        openDialog();
    });

    if (DIALOG_CONFIRM) {
        DIALOG_CONFIRM.addEventListener('click', () => {
            confirmed = true;
            closeDialog();
            FORM.requestSubmit(SUBMIT || undefined);
        });
    }

    if (DIALOG_CANCEL) {
        DIALOG_CANCEL.addEventListener('click', () => {
            confirmed = false;
            closeDialog();
        });
    }

    if (DIALOG) {
        DIALOG.addEventListener('keydown', (event) => {
            if (event.key === 'Escape') {
                confirmed = false;
                closeDialog();
            }
        });
    }

    if (ALL_CELLS) {
        ALL_CELLS.addEventListener('change', () => {
            /*
             * Отметку сняли или вернули после подтверждения: подтверж-
             * дение относится к конкретному составу партии, поэтому
             * его снимают оба действия — иначе проведение ушло бы на
             * сервер без контроля кладовщика.
             */
            confirmed = false;
            render();
        });
    }

    if (NUMBERS_TEXT) {
        NUMBERS_TEXT.addEventListener('input', () => {
            confirmed = false;
            render();
        });
    }

    render();
})();

