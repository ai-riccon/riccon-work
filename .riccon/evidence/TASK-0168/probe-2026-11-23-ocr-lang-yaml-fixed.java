import com.riccon.worker.ocr.config.OcrProperties;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

public class LangYamlProbe {
    public static void main(String[] args) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("test-env", Map.of("RICCON_OCR_LANG", "eng")));
        var sources = new YamlPropertySourceLoader().load("application",
                new FileSystemResource("apps/worker/src/main/resources/application.yml"));
        for (var source : sources) {
            environment.getPropertySources().addLast(source);
        }
        OcrProperties properties = Binder.get(environment)
                .bind("riccon.worker.ocr", OcrProperties.class)
                .get();
        System.out.println("yaml language=" + properties.language()
                + " maxDecodeBytes=" + properties.maxDecodeBytes()
                + " effectiveDecodePixels=" + properties.effectiveDecodePixels());
    }
}
