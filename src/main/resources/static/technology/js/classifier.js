(() => {
  "use strict";
  document.addEventListener("DOMContentLoaded", () => {
    const type = document.getElementById("productType");
    if (!type) return;
    const form = type.closest("form");
    const sensorField = document.getElementById("sensorCatalogField");
    const sensor = document.getElementById("sensorCatalogId");
    const section = document.getElementById("inclusionSection");
    const container = document.getElementById("inclusionRows");
    const template = document.getElementById("inclusionRowTemplate");
    if (!form || !sensorField || !sensor || !section || !container || !template) return;

    const allowed = {
      SENSOR: new Set(["CELL"]),
      DEVICE: new Set(["CELL"]),
      SYSTEM: new Set(["SENSOR", "DEVICE"])
    };
    const rows = () => [...container.querySelectorAll(":scope > .inclusion-row")];

    function bind(row) {
      if (row.dataset.bound) return;
      row.dataset.bound = "1";
      row.querySelector(".inclusion-target").addEventListener("change", () => normalize());
      row.querySelector(".remove-inclusion").addEventListener("click", () => {
        row.remove(); normalize();
      });
    }

    function syncRow(row) {
      const target = row.querySelector(".inclusion-target");
      const modeField = row.querySelector(".inclusion-mode-field");
      const mode = row.querySelector(".inclusion-mode");
      const quantity = row.querySelector(".inclusion-quantity");
      const unit = row.querySelector(".inclusion-unit");
      const remove = row.querySelector(".remove-inclusion");
      const active = Boolean(target.value);
      modeField.hidden = !active;
      mode.disabled = !active;
      mode.required = active;
      quantity.disabled = !active;
      unit.disabled = !active;
      remove.hidden = !active;
      if (!active) mode.value = "";
    }

    function filterOptions() {
      const targetTypes = allowed[type.value] || new Set();
      const selected = new Set(rows().map(r => r.querySelector(".inclusion-target").value).filter(Boolean));
      rows().forEach(row => {
        const select = row.querySelector(".inclusion-target");
        const own = select.value;
        [...select.options].forEach(option => {
          if (!option.value) { option.hidden = false; option.disabled = false; return; }
          const wrongType = !targetTypes.has(option.dataset.productType);
          const duplicate = selected.has(option.value) && option.value !== own;
          option.hidden = wrongType;
          option.disabled = wrongType || duplicate;
        });
        const current = select.selectedOptions[0];
        if (own && (!current || !targetTypes.has(current.dataset.productType))) select.value = "";
        syncRow(row);
      });
    }

    function renumber() {
      let index = 0;
      rows().forEach(row => {
        const target = row.querySelector(".inclusion-target");
        const mode = row.querySelector(".inclusion-mode");
        const quantity = row.querySelector(".inclusion-quantity");
        const unit = row.querySelector(".inclusion-unit");
        const controls = [target, mode, quantity, unit];
        if (!target.value) { controls.forEach(c => c.removeAttribute("name")); return; }
        target.id = `inclusion-target-${index}`;
        mode.id = `inclusion-mode-${index}`;
        row.querySelector(".inclusion-target-field label").htmlFor = target.id;
        row.querySelector(".inclusion-mode-field label").htmlFor = mode.id;
        target.name = `inclusions[${index}].targetId`;
        mode.name = `inclusions[${index}].inclusionMode`;
        quantity.name = `inclusions[${index}].quantity`;
        unit.name = `inclusions[${index}].unit`;
        index++;
      });
    }

    function addRow() {
      container.appendChild(template.content.cloneNode(true));
      const row = container.lastElementChild;
      bind(row); syncRow(row);
      return row;
    }

    function normalize() {
      rows().forEach(bind); rows().forEach(syncRow); filterOptions();
      const empty = rows().filter(r => !r.querySelector(".inclusion-target").value);
      empty.slice(1).forEach(r => r.remove());
      let lastEmpty = rows().find(r => !r.querySelector(".inclusion-target").value);
      if (!lastEmpty) lastEmpty = addRow();
      container.appendChild(lastEmpty);
      filterOptions(); renumber();
    }

    function syncType() {
      const isSensor = type.value === "SENSOR";
      const hasComposition = Object.prototype.hasOwnProperty.call(allowed, type.value);
      sensorField.hidden = !isSensor;
      sensor.disabled = !isSensor;
      sensor.required = isSensor;
      if (!isSensor) sensor.value = "";
      section.hidden = !hasComposition;
      if (!hasComposition) container.replaceChildren(); else normalize();
    }

    type.addEventListener("change", syncType);
    form.addEventListener("submit", renumber);
    rows().forEach(bind);
    syncType();
  });
})();
/*
 * Переход к выбранному классификатору
 * на страницах изменения и удаления.
 */
(function () {
    "use strict";

    function initializeClassifierPickers() {
        const pickers = document.querySelectorAll(
            "[data-classifier-picker]"
        );

        pickers.forEach(function (picker) {
            picker.addEventListener("change", function () {
                const baseUrl = picker.dataset.baseUrl;
                const classifierId = picker.value;

                if (!baseUrl) {
                    return;
                }

                if (!classifierId) {
                    window.location.assign(baseUrl);
                    return;
                }

                window.location.assign(
                    baseUrl + "/" + encodeURIComponent(classifierId)
                );
            });
        });
    }

    function initializeDeleteConfirmation() {
        const deleteForm = document.querySelector(
            "[data-delete-classifier-form]"
        );

        if (!deleteForm) {
            return;
        }

        deleteForm.addEventListener("submit", function (event) {
            const confirmed = window.confirm(
                "Удалить выбранный классификатор? " +
                "Это действие нельзя отменить."
            );

            if (!confirmed) {
                event.preventDefault();
            }
        });
    }

    function initializeClassifierOperationPages() {
        initializeClassifierPickers();
        initializeDeleteConfirmation();
    }

    if (document.readyState === "loading") {
        document.addEventListener(
            "DOMContentLoaded",
            initializeClassifierOperationPages
        );
    } else {
        initializeClassifierOperationPages();
    }
})();