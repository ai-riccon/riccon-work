import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.worker.config.ParseProperties;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.domain.ParsedSegment;
import com.riccon.worker.parse.service.TextChunker;
import java.util.List;

public class ChunkerProbe {
    public static void main(String[] args) {
        TextChunker chunker = new TextChunker(new ParseProperties(52428800L, 100, 80));
        String text = "A".repeat(60) + "\n" + "B".repeat(60) + "\n" + "C".repeat(60);
        List<?> chunks = chunker.chunk(new ParsedContent(
                List.of(new ParsedSegment(text, LocatorType.SHEET, null, "S", "A1", null)), null, 1, "test"));
        System.out.println("chunkCount=" + chunks.size() + " textLength=" + text.length());
        System.out.println("contents=" + chunks.stream()
                .map(chunk -> ((com.riccon.worker.parse.domain.ParsedChunk) chunk).content())
                .toList());
    }
}
