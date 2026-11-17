package com.riccon.worker.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * 测试文档生成器。
 *
 * <p>用真实 PDFBox/POI 生成最小有效文档，确保解析测试覆盖实际文件格式，而不是伪造扩展名。
 * 图片只生成 1x1 像素；M02 只做元数据，不需要 OCR 内容。
 */
public final class TestDocuments {

    /** 工具类禁止实例化。 */
    private TestDocuments() {}

    /**
     * 生成多页 PDF，每页包含可抽取文本。
     *
     * @param pages 页数
     * @return PDF 字节
     */
    public static byte[] pdf(int pages) {
        try (PDDocument document = new PDDocument()) {
            for (int pageNo = 1; pageNo <= pages; pageNo++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(72, 700);
                    stream.showText("Page " + pageNo + " revenue analysis content");
                    stream.endText();
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create test pdf", ex);
        }
    }

    /**
     * 生成包含段落与表格的 DOCX。
     *
     * @return DOCX 字节
     */
    public static byte[] docx() {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Market overview paragraph.");
            document.createParagraph().createRun().setText("Competitor pricing paragraph.");
            XWPFTable table = document.createTable(2, 2);
            XWPFTableRow header = table.getRow(0);
            header.getCell(0).setText("Region");
            header.getCell(1).setText("Revenue");
            XWPFTableRow data = table.getRow(1);
            data.getCell(0).setText("APAC");
            data.getCell(1).setText("120");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create test docx", ex);
        }
    }

    /**
     * 生成包含两个工作表的 XLSX。
     *
     * @return XLSX 字节
     */
    public static byte[] xlsx() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet budget = workbook.createSheet("Budget");
            Row header = budget.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            Row data = budget.createRow(1);
            data.createCell(0).setCellValue("Cloud");
            data.createCell(1).setCellValue(1200);
            Sheet summary = workbook.createSheet("Summary");
            Row summaryRow = summary.createRow(0);
            summaryRow.createCell(0).setCellValue("Total");
            summaryRow.createCell(1).setCellValue(1200);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create test xlsx", ex);
        }
    }

    /**
     * 生成 1x1 PNG。
     *
     * @return PNG 字节
     */
    public static byte[] png() {
        return image("png");
    }

    /**
     * 生成 1x1 JPEG。
     *
     * @return JPEG 字节
     */
    public static byte[] jpeg() {
        return image("jpg");
    }

    /**
     * 生成损坏的 PDF，用于失败与重试测试。
     *
     * @return 非法 PDF 字节
     */
    public static byte[] corruptPdf() {
        return "%PDF-1.7\nthis is not a real pdf body".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 生成 1x1 图片。
     *
     * @param format ImageIO 格式名
     * @return 图片字节
     */
    private static byte[] image(String format) {
        try {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("no image writer for format " + format);
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create test image", ex);
        }
    }
}
