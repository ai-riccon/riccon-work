import com.riccon.worker.config.OpenSearchProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
var env = new StandardEnvironment();
var props = new Binder(ConfigurationPropertySources.get(env)).bind("riccon.worker.opensearch", Bindable.of(OpenSearchProperties.class)).orElse(null);
System.out.println("BOUND_INDEX_VERSION=" + (props == null ? "NULL" : props.indexVersion()));
System.out.println("ENV_OPENSEARCH_INDEX_VERSION=" + System.getenv("RICCON_OPENSEARCH_INDEX_VERSION"));
System.out.println("ENV_WORKER_OPENSEARCH_INDEX_VERSION=" + System.getenv("RICCON_WORKER_OPENSEARCH_INDEX_VERSION"));
/exit
