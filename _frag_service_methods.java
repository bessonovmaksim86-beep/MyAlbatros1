
    /**
     * Комплектовочные операции изделия конкретного серийного номера.
     *
     * Операции берутся из базового техпроцесса классификатора серийника,
     * а не из выпуска: именно маршрут изделия-датчика задаёт, в каких
     * операциях комплектования он участвует. Пустой список означает, что
     * у серийника нет изделия или изделие не имеет базового маршрута —
     * диспетчер увидит пустой выбор и поймёт, что маршрут не настроен.
     */
    @Transactional(readOnly = true)
    public List<Operation> findSerialKittingOperations(Long serialId) {
        if (serialId == null) {
            return List.of();
        }

        ProductSerial serial = productSerialRepository
                .findById(serialId)
                .orElse(null);

        if (serial == null || serial.getProductClassifier() == null) {
            return List.of();
        }

        List<Operation> operations = new ArrayList<>();

        for (TechProcessOperation node
                : techProcessOperationRepository
                .findBasicProcessKittingByClassifier(
                        serial.getProductClassifier().getId(),
                        OperationTypeCode.KITTING.name())) {

            if (node.getOperation() != null) {
                operations.add(node.getOperation());
            }
        }

        return operations;
    }

    /**
     * Создаёт заказы-комплектующие по отмеченным парам
     * «серийник + операция»: каждой паре — отдельный ЗНП.
     *
     * Номер задаётся вручную (он приходит из внешнего учёта), поэтому
     * автоподбор для комплектования не выполняется, а пустой номер
     * отклоняется. Каждый ЗНП привязывается к ровно одному серийнику
     * родителя через SerialProductionOrder.
     *
     * Операция обязана быть комплектовочной и входить в базовый маршрут
     * изделия этого серийника — иначе ЗНП повесил бы операцию на чужое
     * изделие.
     */
    @Transactional
    public List<ProductionOrder> createKittingOrders(
            Long releaseId,
            List<Map<String, Object>> pairs,
            AppUser author
    ) {
        if (releaseId == null || pairs == null || pairs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Не выбрано ни одной пары «серийник + операция»"
            );
        }

        ProductionOrder release = productionOrderRepository
                .findById(releaseId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Заказ верхнего уровня не найден"
                ));

        if (!release.isRoot()) {
            throw new IllegalStateException(
                    "Заказы-комплектующие создают только внутри выпуска"
            );
        }

        CustomerOrder customerOrder = requireCustomerOrder(release);
        LocalDate dueDate = resolveDueDate(customerOrder);

        List<ProductionOrder> created = new ArrayList<>();

        for (Map<String, Object> pair : pairs) {
            Long serialId = toLongOrNull(pair.get("serialId"));
            Long operationId = toLongOrNull(pair.get("operationId"));
            String number = trimToNullString(pair.get("number"));

            ProductSerial serial =
                    requireSerialOfRelease(serialId, release);
            Operation operation =
                    requireKittingOperation(operationId);

            requireSerialOperationAllowed(serial, operation);

            if (number == null) {
                throw new IllegalArgumentException(
                        "Укажите номер заказа для серийника «"
                                + serial.getFullNumber() + "»"
                );
            }

            if (productionOrderRepository.existsByNumber(number)) {
                throw new IllegalArgumentException(
                        "Заказ с номером «" + number
                                + "» уже существует"
                );
            }

            ProductionOrder order = new ProductionOrder();

            order.setNumber(number);
            order.setCustomerOrder(customerOrder);
            order.setParentProductionOrder(release);
            order.setZnpKind(ZnpKind.KITTING);
            order.setProductClassifier(serial.getProductClassifier());
            order.setProductionOperation(operation);
            order.setOrderDate(LocalDate.now());
            order.setDueDate(dueDate);
            order.setStatus(ProductionOrderStatus.NEW);
            order.setCreatedBy(author);
            order.setActive(true);

            ProductionOrder saved =
                    productionOrderRepository.save(order);

            serialKittingService.attach(
                    saved,
                    List.of(serial.getId()),
                    operation,
                    author
            );

            created.add(saved);
        }

        return created;
    }

    /** Серийник обязан быть выпущен именно этим заказом верхнего уровня. */
    private ProductSerial requireSerialOfRelease(
            Long serialId,
            ProductionOrder release
    ) {
        if (serialId == null) {
            throw new IllegalArgumentException(
                    "Серийный номер не выбран"
            );
        }

        ProductSerial serial = productSerialRepository
                .findById(serialId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Серийный номер не найден"
                ));

        Long issuedOrderId = serial.getIssuedProductionOrder() == null
                ? null
                : serial.getIssuedProductionOrder().getId();

        if (!release.getId().equals(issuedOrderId)) {
            throw new IllegalArgumentException(
                    "Датчик «" + serial.getFullNumber()
                            + "» выпущен другим заказом"
            );
        }

        return serial;
    }

    /**
     * Операция обязана входить в базовый маршрут изделия серийника —
     * тот же источник, что и список в findSerialKittingOperations.
     */
    private void requireSerialOperationAllowed(
            ProductSerial serial,
            Operation operation
    ) {
        if (serial.getProductClassifier() == null) {
            throw new IllegalArgumentException(
                    "У датчика «" + serial.getFullNumber()
                            + "» нет изделия, а значит и маршрута"
            );
        }

        boolean allowed = findSerialKittingOperations(serial.getId())
                .stream()
                .anyMatch(op -> op.getId().equals(operation.getId()));

        if (!allowed) {
            throw new IllegalArgumentException(
                    "Операция не входит в маршрут изделия датчика «"
                            + serial.getFullNumber() + "»"
            );
        }
    }

    private static Long toLongOrNull(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            return number.longValue();
        }

        String text = value.toString().trim();

        if (text.isEmpty()) {
            return null;
        }

        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String trimToNullString(Object value) {
        if (value == null) {
            return null;
        }

        String text = value.toString().trim();

        return text.isEmpty() ? null : text;
    }
