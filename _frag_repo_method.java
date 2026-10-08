
    /**
     * Комплектовочные операции базового техпроцесса, назначенного
     * классификатору серийника.
     *
     * Нужны при создании ЗНП для конкретного серийника: список операций
     * берётся из маршрута изделия-датчика, а не из выпуска. Учитывается
     * только базовый активный маршрут, только операции типа KITTING,
     * порядок — по позиции в маршруте.
     */
    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "operation.operationType"
    })
    @Query("""
            select node
            from TechProcessOperation node
            join node.techProcess techProcess
            join TechProcessClassifier link
                 on link.techProcess.id = techProcess.id
            join node.operation operation
            join operation.operationType operationType
            where link.classifier.id = :classifierId
              and link.basic = true
              and techProcess.active = true
              and operationType.code = :typeCode
            order by node.position asc
            """)
    List<TechProcessOperation> findBasicProcessKittingByClassifier(
            @Param("classifierId") Long classifierId,
            @Param("typeCode") String typeCode
    );
