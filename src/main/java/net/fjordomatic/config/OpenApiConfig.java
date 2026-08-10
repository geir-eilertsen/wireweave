package net.fjordomatic.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private final ConfigResolver configResolver;

    public OpenApiConfig(ConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    @Bean
    public OpenAPI fjordOpenAPI() {
        String domain = configResolver.getDomain();
        OpenAPI api = new OpenAPI()
                .info(new Info()
                        .title("Fjord API")
                        .description("Effortless WireGuard mesh networking")
                        .version("1.0.0"))
                .addServersItem(new Server().url("http://localhost:8080").description("Local server"));
        if (domain != null && !domain.isBlank()) {
            api.addServersItem(new Server().url("https://vaier." + domain).description("Production server"));
        }
        return api;
    }
}
