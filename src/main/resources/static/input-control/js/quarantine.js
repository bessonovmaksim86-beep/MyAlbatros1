(() => {
    'use strict';

    /*
     * Выбор ячеек изолятора и перемещение их на склад полуфабрикатов.
     *
     * Счётчик и состояние кнопки считают по чекбоксам, а не держат
     * своё число: строки размечают в обе стороны (вручную и «все»), и
     * любое расхождение цифры с фактическим составом формы кончилось бы
     * перемещением не на тот перечень.
     *
     * Кнопка «Переместить» появляется только при выбранной хотя бы
     * одной ячейке: пустое перемещение отклоняет сервис, но видимый
     * смысл кнопки человек должен считывать до отправки длинной формы.
     *
     * Состав и причину подтверждают отдельным окном: перемещённую
     * ячейку в изоляторе уже не видно, а причину после проведения не
     * изменить — поэтому первый запуск формы только показывает
     * подтверждение и ничего не отправляет.
     */
    const FORM = document.getElementById('bvkTransferForm');

    if (!FORM) {
        return;
    }

    const boxes = Array.from(
        FORM.querySelectorAll('.bvk-check__input')
    );

    /* Строки «уже передана на склад» заблокированы и в отбор не входят. */
    const pickable = boxes.filter((box) => !box.disabled);

    const checkAll = document.getElementById('bvkCheckAll');
    const bar = document.getElementById('bvkTransferBar');
    const counter = document.getElementById('bvkPickedCount');
    const clearButton = document.getElementById('bvkClearPicked');
    const openButton = document.getElementById('bvkTransferOpen');

    const dialog = document.getElementById('bvkTransferDialog');
    const dialogCount = document.getElementById('bvkTransferDialogCount');
    const dialogList = document.getElementById('bvkTransferDialogList');
    const reasonInput = document.getElementById('bvkTransferReasonInput');
    const reasonField = document.getElementById('bvkTransferReasonField');
    const dialogError = document.getElementById('bvkTransferDialogError');
    const confirmButton = document.getElementById('bvkTransferDialogConfirm');
    const cancelButton = document.getElementById('bvkTransferDialogCancel');

    function selectedBoxes() {
        return pickable.filter((box) => box.checked);
    }

    /*
     * Номер для перечня в окне: значение чекбокса — пара
     * «группа|номер», человеку нужен только номер.
     */
    function numberOf(box) {
        const value = box.value || '';

        return value.includes('|')
            ? value.slice(value.indexOf('|') + 1)
            : value;
    }

    function render() {
        const count = selectedBoxes().length;

        if (counter) {
            counter.textContent = 'Отмечено ячеек: ' + count;
        }

        /*
         * Панель прячут при нулевом выборе: висевшая над перечнем
         * кнопка «Переместить» без отмеченных ячеек только спорол бы
         * вид реестра.
         */
        if (bar) {
            bar.hidden = count === 0;
        }

        if (checkAll) {
            checkAll.checked =
                pickable.length > 0 && count === pickable.length;

            /*
             * Половинчатое состояние показывают галочкой с тире: без
             * него заголовок таблицы выглядел бы пустым, хотя часть
             * перечня уже размечена.
             */
            checkAll.indeterminate = count > 0 && count < pickable.length;
            checkAll.disabled = pickable.length === 0;
        }

        /*
         * Отмеченную строку подсвечивают: перечень длинный, и без
         * подсветки отмеченное теряется при прокрутке.
         */
        boxes.forEach((box) => {
            const row = box.closest('tr');

            if (row) {
                row.classList.toggle('is-picked', box.checked);
            }
        });
    }

    FORM.addEventListener('change', (event) => {
        if (event.target === checkAll) {
            pickable.forEach((box) => {
                box.checked = checkAll.checked;
            });
        }

        render();
    });

    if (clearButton) {
        clearButton.addEventListener('click', () => {
            boxes.forEach((box) => {
                box.checked = false;
            });

            render();
        });
    }

    /* =========================================================
       ПОДТВЕРЖДЕНИЕ ПЕРЕМЕЩЕНИЯ
       ========================================================= */

    function openDialog() {
        const chosen = selectedBoxes();

        if (chosen.length === 0) {
            return;
        }

        if (dialogCount) {
            dialogCount.textContent = String(chosen.length);
        }

        if (dialogList) {
            dialogList.textContent = '';

            chosen.forEach((box) => {
                const item = document.createElement('li');

                item.className = 'bvk-transfer-dialog__item';
                item.textContent = numberOf(box);
                dialogList.appendChild(item);
            });
        }

        if (dialogError) {
            dialogError.hidden = true;
        }

        if (dialog) {
            dialog.hidden = false;

            if (reasonInput) {
                reasonInput.focus();
            }
        }
    }

    function closeDialog() {
        if (dialog) {
            dialog.hidden = true;

            if (openButton) {
                openButton.focus();
            }
        }
    }

    if (openButton) {
        openButton.addEventListener('click', openDialog);
    }

    if (cancelButton) {
        cancelButton.addEventListener('click', closeDialog);
    }

    if (confirmButton) {
        confirmButton.addEventListener('click', () => {
            const reason = reasonInput
                ? reasonInput.value.trim()
                : '';

            /*
             * Пустую причину не отправляют на сервер: сервис её
             * отклонит, но человек увидит объяснение сразу в окне, а
             * не после перезагрузки длинного перечня.
             */
            if (!reason) {
                if (dialogError) {
                    dialogError.hidden = false;
                }

                if (reasonInput) {
                    reasonInput.focus();
                }

                return;
            }

            if (reasonField) {
                reasonField.value = reason;
            }

            closeDialog();
            FORM.submit();
        });
    }

    if (dialog) {
        dialog.addEventListener('keydown', (event) => {
            if (event.key === 'Escape') {
                closeDialog();
            }
        });
    }

    if (reasonInput) {
        reasonInput.addEventListener('input', () => {
            if (dialogError) {
                dialogError.hidden = true;
            }
        });
    }

    render();
})();
