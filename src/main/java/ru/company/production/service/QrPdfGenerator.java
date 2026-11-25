package ru.company.production.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;
import ru.company.production.entity.CellReceivingBatch;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Пробный PDF с QR-кодами для проверки без принтера этикеток.
 *
 * BarTender умеет ставить задание только на принтер, а проверять
 * вёрстку этикетки на машине разработчика или без принтера нужно.
 * Поэтому приложение само собираёт PDF, близкий к боевой этикетке:
 * QR по центру и — когда у классификатора включён «номер над QR» —
 * числовой номер текстом над кодом.
 *
 * Результат отдаётся оператору как файл: его открывают в любом
 * просмотрщике, печатают на обычном принтере и сканируют QR с листа
 * или с экрана. Это не заменяет BarTender, а проверяет содержимое
 * (номера и состав QR) до похода к принтеру.
 */
@Service
public class QrPdfGenerator {

    /** Размер страницы этикетки, pt: примерно 50 × 70 мм. */
    private static final float PAGE_WIDTH = 142f;

    private static final float PAGE_HEIGHT = 198f;

    /** Сторона QR на этикетке, pt. */
    private static final float QR_SIZE = 95f;

    /** Отступ числового номера от QR, pt. */
    private static final float CAPTION_GAP = 14f;

    /** Кегль числового номера, pt. */
    private static final float CAPTION_FONT_SIZE = 12f;

    private static final Map<EncodeHintType, Object> HINTS = new HashMap<>();

    static {
        HINTS.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        HINTS.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        HINTS.put(EncodeHintType.MARGIN, 1);
    }

    /**
     * Собирает PDF партии: одна страница — одна этикетка.
     *
     * Номера берутся из партии целиком, поэтому пробник печатает
     * ровно тот комплект, что ушёл бы на принтер.
     */
    public byte[] generate(CellReceivingBatch batch) throws IOException {

        List<String> numbers = batch.fullNumbers();

        if (numbers.isEmpty()) {
            throw new IOException("В партии нет номеров — печатать нечего");
        }

        boolean showNumber = batch.getCellClassifier() != null
                && batch.getCellClassifier().isPrintNumberOverQr();

        try (PDDocument document = new PDDocument()) {

            for (String number : numbers) {
                drawLabel(document, number, showNumber);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();

            document.save(out);

            return out.toByteArray();
        }
    }

    /** Имя файла пробника — по диапазону партии, чтобы не путать листы. */
    public String fileName(CellReceivingBatch batch) {
        return "qr-" + batch.getId() + "-"
                + batch.fullNumber(batch.getFirstSerial())
                + ".."
                + batch.fullNumber(batch.getLastSerial())
                + ".pdf";
    }

    private void drawLabel(PDDocument document, String number,
                           boolean showNumber) throws IOException {

        PDPage page = new PDPage(
                new PDRectangle(PAGE_WIDTH, PAGE_HEIGHT)
        );

        document.addPage(page);

        BufferedImage qr = renderQr(number);
        PDImageXObject image = LosslessFactory.createFromImage(document, qr);

        float qrX = (PAGE_WIDTH - QR_SIZE) / 2;

        /*
         * С номером-подписью QR уезжает вниз, без него — в центр.
         * Порядок координат PDF: снизу вверх, поэтому подпись ищется
         * над квадратом кода, а не под ним.
         */
        float qrY = showNumber
                ? (PAGE_HEIGHT - QR_SIZE - CAPTION_GAP - CAPTION_FONT_SIZE) / 2
                : (PAGE_HEIGHT - QR_SIZE) / 2;

        try (PDPageContentStream content = new PDPageContentStream(document, page)) {

            content.drawImage(image, qrX, qrY, QR_SIZE, QR_SIZE);

            if (showNumber) {
                PDType1Font font =
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

                /*
                 * getStringWidth отдаёт ширину в тысячных долях кегля,
                 * поэтому делитель именно 1000: при 100 посчитанная
                 * ширина превысила бы лист, и «центр» уехал бы за его
                 * левый край — текст оставался бы в файле, но не
                 * был бы виден ни на экране, ни на печати.
                 */
                float textWidth = font.getStringWidth(number) / 1000f
                        * CAPTION_FONT_SIZE;

                content.setFont(font, CAPTION_FONT_SIZE);
                content.setNonStrokingColor(Color.BLACK);
                content.beginText();
                content.newLineAtOffset(
                        (PAGE_WIDTH - textWidth) / 2,
                        qrY + QR_SIZE + CAPTION_GAP
                );
                content.showText(number);
                content.endText();
            }
        }
    }

    private BufferedImage renderQr(String number) {

        BitMatrix matrix;

        try {
            matrix = new QRCodeWriter().encode(
                    number, BarcodeFormat.QR_CODE, 400, 400, HINTS
            );
        } catch (com.google.zxing.WriterException exception) {
            throw new IllegalStateException(
                    "Не удалось построить QR для номера " + number, exception
            );
        }

        int width = matrix.getWidth();
        int height = matrix.getHeight();

        BufferedImage image = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_RGB
        );

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y,
                        matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }

        return image;
    }
}
