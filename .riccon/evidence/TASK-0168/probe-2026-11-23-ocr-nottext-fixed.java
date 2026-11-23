import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.*;
import com.riccon.worker.ocr.service.*;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.service.PdfDocumentParser;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

public class NoTextProbeFixed {
    public static void main(String[] args) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
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
                    return new OcrResult(List.of(), 100, 100);
                }
            };
            OcrService service = new OcrService(engine, props, new OcrImagePreprocessor(props));
            FileRecord file = new FileRecord(UUID.randomUUID(), "blank.pdf", FileFormat.PDF,
                    "application/pdf", pdf.length, "tenants/test/file/2026/object");
            try {
                new PdfDocumentParser(service, props, Clock.systemUTC()).parse(file, pdf);
                System.out.println("NO_EXCEPTION");
            } catch (ParseException error) {
                System.out.println("errorCode=" + error.errorCode() + " retryable=" + error.retryable());
            } finally {
                service.close();
            }
        }
    }
}
