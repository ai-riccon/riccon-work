import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.*;
import com.riccon.worker.ocr.service.*;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.service.PdfDocumentParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

public class OomProbeFixed {
    public static void main(String[] args) throws Exception {
        System.out.println("maxHeapMiB=" + Runtime.getRuntime().maxMemory() / 1024 / 1024);
        System.out.println("pagePoints=5000x5000 pdfDpi=200 requestedPixels=192904321 maxDecodePixels=200000000 effectiveDecodePixels=16000000");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(5000f, 5000f)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            byte[] pdf = out.toByteArray();
            OcrProperties props = new OcrProperties(true, "tesseract", "chi_sim+eng", 1,
                    Duration.ofSeconds(30), Duration.ofSeconds(600), 100,
                    50_000_000L, 200_000_000L, 67_108_864L, 4000, 200,
                    new BigDecimal("0.60"), new BigDecimal("0.45"), "", "");
            OcrEngine engine = new OcrEngine() {
                public String engineId() { return "fake"; }
                public boolean languageAvailable() { return true; }
                public OcrResult recognize(byte[] bytes, OcrOptions options) {
                    try {
                        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                        System.out.println("rendered=" + image.getWidth() + "x" + image.getHeight()
                                + " pixels=" + ((long) image.getWidth() * image.getHeight()));
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                    return new OcrResult(List.of(new OcrBlock("page", new BigDecimal("0.900"),
                            new com.riccon.worker.parse.domain.BoundingBox(0, 0, 10, 10), 1, 1)), 100, 100);
                }
            };
            OcrService service = new OcrService(engine, props, new OcrImagePreprocessor(props));
            FileRecord file = new FileRecord(UUID.randomUUID(), "near-limit.pdf", FileFormat.PDF,
                    "application/pdf", pdf.length, "tenants/test/file/2026/object");
            try {
                ParsedContent parsed = new PdfDocumentParser(service, props, Clock.systemUTC()).parse(file, pdf);
                System.out.println("NO_OOM pages=" + parsed.pageCount() + " segments=" + parsed.segments().size());
            } catch (Throwable error) {
                System.out.println("THROWN " + error.getClass().getName() + ": " + error.getMessage());
            } finally {
                service.close();
            }
        }
    }
}
