import org.apache.poi.xssf.usermodel.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

byte[] baseWorkbook() throws Exception {
    try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
        var s = wb.createSheet("Data");
        s.createRow(0).createCell(0).setCellValue("first");
        s.createRow(1).createCell(0).setCellValue("second");
        s.createRow(2).createCell(0).setCellValue("third");
        wb.write(out);
        return out.toByteArray();
    }
}

byte[] rewrite(byte[] src, java.util.function.BiFunction<String, byte[], byte[]> fn) throws Exception {
    try (var zin = new ZipInputStream(new ByteArrayInputStream(src));
         var out = new ByteArrayOutputStream();
         var zout = new ZipOutputStream(out)) {
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            byte[] b = zin.readAllBytes();
            byte[] nb = fn.apply(e.getName(), b);
            zout.putNextEntry(new ZipEntry(e.getName()));
            zout.write(nb);
            zout.closeEntry();
        }
        zout.finish();
        return out.toByteArray();
    }
}

byte[] stripCount(byte[] src) throws Exception {
    return rewrite(src, (name, b) -> {
        if (!name.equals("xl/sharedStrings.xml")) return b;
        String xml = new String(b, StandardCharsets.UTF_8);
        return xml.replaceAll(" count=\"[0-9]+\"", "").replaceAll(" uniqueCount=\"[0-9]+\"", "")
                .getBytes(StandardCharsets.UTF_8);
    });
}

byte[] stripRowRefs(byte[] src) throws Exception {
    return rewrite(src, (name, b) -> {
        if (!name.equals("xl/worksheets/sheet1.xml")) return b;
        String xml = new String(b, StandardCharsets.UTF_8);
        return xml.replaceAll("<row r=\"[0-9]+\"", "<row").getBytes(StandardCharsets.UTF_8);
    });
}

var parser = new com.riccon.worker.parse.service.XlsxDocumentParser();

com.riccon.worker.parse.domain.FileRecord rec(byte[] b) {
    return new com.riccon.worker.parse.domain.FileRecord(
            UUID.randomUUID(), "sample.xlsx",
            com.riccon.platform.domain.file.FileFormat.XLSX,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            b.length, "tenants/test/file/2026/object");
}

void probe(String label, byte[] bytes) {
    try {
        var parsed = parser.parse(rec(bytes), bytes);
        System.out.println(label + " => OK segments=" + parsed.segments().size());
        parsed.segments().forEach(s -> System.out.println("   [" + s.cellRange() + "] " + s.content()));
        System.out.println("   warnings=" + parsed.warnings());
        System.out.println("   sheetSummary=" + (parsed.summary() == null ? null : parsed.summary().sheets()));
    } catch (Exception ex) {
        System.out.println(label + " => ERR " + ex.getClass().getName() + ": " + ex.getMessage());
    }
}

byte[] plain = baseWorkbook();
probe("baseline", plain);
probe("sharedStrings without count/uniqueCount", stripCount(plain));
probe("rows without r attribute", stripRowRefs(plain));
/exit
