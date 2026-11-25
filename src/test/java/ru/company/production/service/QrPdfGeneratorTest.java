package ru.company.production.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.company.production.entity.CellReceivingBatch;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверка пробного PDF с QR-кодами.
 *
 * Тест проверяет, что генератор создаёт валидный PDF с корректным
 * числом страниц, корректным именем файла и что пустая партия
 * вызывает ошибку.
 */
class QrPdfGeneratorTest {

    private final QrPdfGenerator generator = new QrPdfGenerator();

    @Test
    @DisplayName("Создаёт PDF: три номера — три страницы")
    void generateCreatesPdfWithQrCodes() throws Exception {
        CellReceivingBatch batch = makeBatch("121200022", "121200023", "121200024");

        byte[] pdfBytes = generator.generate(batch);

        assertTrue(pdfBytes.length > 0, "PDF не пустой");

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            assertEquals(3, document.getNumberOfPages(),
                    "Три номера — три страницы");
        }
    }

    @Test
    @DisplayName("Пустая партия выбрасывает исключение")
    void emptyBatchThrows() {
        CellReceivingBatch batch = new CellReceivingBatch();
        batch.setIncomingOrderNumber("2026-0042");
        batch.setFirstSerial(5);
        batch.setLastSerial(3);

        assertThrows(Exception.class, () -> generator.generate(batch));
    }

    @Test
    @DisplayName("Имя файла содержит id и диапазон номеров")
    void fileNameContainsRange() {
        CellReceivingBatch batch = makeBatch("121200022", "121200023");

        String name = generator.fileName(batch);

        assertTrue(name.startsWith("qr-"));
        assertTrue(name.endsWith(".pdf"));
        assertTrue(name.contains("121200022"));
        assertTrue(name.contains("121200023"));
    }

    @Test
    @DisplayName("PDF открывается без ошибок, страницы не null")
    void pdfOpensWithoutError() throws Exception {
        CellReceivingBatch batch = makeBatch(
                "121200001", "121200002", "121200003", "121200004", "121200005"
        );

        byte[] pdfBytes = generator.generate(batch);

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            assertEquals(5, document.getNumberOfPages());

            for (int i = 0; i < document.getNumberOfPages(); i++) {
                PDPage page = document.getPage(i);
                assertNotNull(page, "Страница " + i + " не null");
            }
        }
    }

    @Test
    @DisplayName("Без флага номер над QR не печатается")
    void numberAbsentWithoutFlag() throws Exception {
        CellReceivingBatch batch = makeBatch("121200010", "121200011");

        byte[] pdfBytes = generator.generate(batch);

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            assertEquals(2, document.getNumberOfPages());
            assertTrue(captions(document).isEmpty(),
                    "Без флага текстовых номеров быть не должно");
        }
    }

    /**
     * С включённым флагом номер обязан быть не просто в файле, а
     * внутри листа: координата считается от ширины текста, и ошибка
     * в ней уводила подпись за левый край — просмотрщик показывал
     * пустое место, хотя текст в PDF присутствовал.
     */
    @Test
    @DisplayName("С флагом номер напечатан над QR и в пределах листа")
    void numberPrintedInsidePage() throws Exception {
        CellReceivingBatch batch = makeBatch("121200010", "121200011");
        batch.getCellClassifier().setPrintNumberOverQr(true);

        byte[] pdfBytes = generator.generate(batch);

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            assertEquals(2, document.getNumberOfPages());

            List<Caption> found = captions(document);
            assertEquals(2, found.size(), "По одному номеру на страницу");

            for (Caption place : found) {
                assertTrue(place.x() >= 0 && place.x() <= PAGE_WIDTH,
                        "Номер по горизонтали в листе, получено x=" + place.x());
                assertTrue(place.y() >= 0 && place.y() <= PAGE_HEIGHT,
                        "Номер по вертикали в листе, получено y=" + place.y());
                assertTrue(place.y() < PAGE_HEIGHT / 2,
                        "Номер в верхней половине страницы — над QR, "
                                + "получено y=" + place.y());
            }

            assertEquals("121200010", found.get(0).text());
            assertEquals("121200011", found.get(1).text());
        }
    }

    /** Размер листа этикетки, pt — те же константы, что в генераторе. */
    private static final float PAGE_WIDTH = 142f;
    private static final float PAGE_HEIGHT = 198f;

    /** Текстовая подпись: сам текст и его левый верхний угол, pt. */
    private record Caption(String text, float x, float y) {
    }

    /**
     * Собирает непустые текстовые фрагменты PDF вместе с их
     * координатами, отсчитанными от верхнего левого угла страницы.
     */
    private List<Caption> captions(PDDocument document) throws Exception {
        List<Caption> found = new ArrayList<>();

        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                if (text.isBlank()) {
                    return;
                }
                TextPosition first = positions.get(0);
                found.add(new Caption(
                        text,
                        first.getXDirAdj(),
                        first.getYDirAdj()
                ));
            }
        };

        stripper.setStartPage(1);
        stripper.setEndPage(document.getNumberOfPages());
        stripper.getText(document);

        return found;
    }

    private CellReceivingBatch makeBatch(String... numbers) {
        CellReceivingBatch batch = new CellReceivingBatch();
        batch.setIncomingOrderNumber("2026-0042");
        batch.setQuantity(numbers.length);

        int first = Integer.parseInt(numbers[0].substring(4));
        int last  = Integer.parseInt(numbers[numbers.length - 1].substring(4));
        batch.setFirstSerial(first);
        batch.setLastSerial(last);

        ProductClassifier classifier = new ProductClassifier();
        classifier.setCode(1212);
        classifier.setProductType(ProductType.CELL);
        classifier.setName("ЯПР56");
        batch.setCellClassifier(classifier);

        return batch;
    }
}
