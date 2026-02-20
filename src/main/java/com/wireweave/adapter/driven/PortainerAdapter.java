package com.wireweave.adapter.driven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wireweave.domain.DockerService;
import com.wireweave.domain.port.ForGettingDockerInfo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PortainerAdapter implements ForGettingDockerInfo {

    private String portainerUrl;
    private final String apiToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PortainerAdapter() {
        this.portainerUrl = System.getenv("WIREWEAVE_PORTAINER_URL");
        if (portainerUrl == null || portainerUrl.isEmpty()) {
            this.portainerUrl = "http://wireguard.home:9000";
        } else {
            this.portainerUrl = portainerUrl;
        }

        this.apiToken = System.getenv("WIREWEAVE_PORTAINER_TOKEN");
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<DockerService> getServicesWithExposedPorts() {
        try {
            // First, get the endpoint ID (usually 1 or 2 for local Docker)
            int endpointId = getEndpointId();

            // Get list of containers from Portainer API
            String containersUrl = portainerUrl + "/api/endpoints/" + endpointId + "/docker/containers/json";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(containersUrl))
                    .GET();

            if (apiToken != null && !apiToken.isEmpty()) {
                requestBuilder.header("X-API-Key", apiToken);
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to get containers from Portainer. Status: " +
                        response.statusCode() + ", Body: " + response.body());
            }

            // Parse response
            PortainerContainer[] containers = objectMapper.readValue(response.body(), PortainerContainer[].class);

            List<DockerService> services = new ArrayList<>();
            for (PortainerContainer container : containers) {
                if (container.ports != null && container.ports.length > 0) {
                    List<DockerService.PortMapping> portMappings = new ArrayList<>();

                    for (PortainerPort port : container.ports) {
                        portMappings.add(new DockerService.PortMapping(
                                port.privatePort,
                                port.publicPort,
                                port.type != null ? port.type : "tcp",
                                port.ip
                        ));
                    }

                    String containerName = extractContainerName(container.names);

                    services.add(new DockerService(
                            container.id,
                            containerName,
                            container.image,
                            portMappings
                    ));
                }
            }

            return services;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to get Docker services from Portainer at " + portainerUrl, e);
        }
    }

    private int getEndpointId() throws IOException, InterruptedException {
        String endpointsUrl = portainerUrl + "/api/endpoints";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpointsUrl))
                .GET();

        if (apiToken != null && !apiToken.isEmpty()) {
            requestBuilder.header("X-API-Key", apiToken);
        }

        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to get endpoints from Portainer. Status: " +
                    response.statusCode() + ". Body: " + response.body());
        }

        System.out.println("DEBUG: Endpoints response: " + response.body());

        PortainerEndpoint[] endpoints = objectMapper.readValue(response.body(), PortainerEndpoint[].class);

        if (endpoints.length == 0) {
            throw new RuntimeException("No endpoints found in Portainer");
        }

        System.out.println("DEBUG: Found " + endpoints.length + " endpoint(s). Using endpoint ID: " + endpoints[0].id + " (name: " + endpoints[0].name + ")");

        // Return the first endpoint (usually the local Docker environment)
        return endpoints[0].id;
    }

    private String extractContainerName(String[] names) {
        if (names == null || names.length == 0) {
            return "unknown";
        }
        // Docker names start with '/', so remove it
        String name = names[0];
        return name.startsWith("/") ? name.substring(1) : name;
    }

    // JSON mapping classes
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PortainerContainer {
        @JsonProperty("Id")
        public String id;
        @JsonProperty("Names")
        public String[] names;
        @JsonProperty("Image")
        public String image;
        @JsonProperty("Ports")
        public PortainerPort[] ports;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PortainerPort {
        @JsonProperty("PrivatePort")
        public int privatePort;
        @JsonProperty("PublicPort")
        public Integer publicPort;
        @JsonProperty("Type")
        public String type;
        @JsonProperty("IP")
        public String ip;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PortainerEndpoint {
        @JsonProperty("Id")
        public int id;
        @JsonProperty("Name")
        public String name;
    }

    public static void main(String[] args) {
        PortainerAdapter adapter = new PortainerAdapter();

        System.out.println("Connecting to Portainer at: " + adapter.portainerUrl);

        List<DockerService> services = adapter.getServicesWithExposedPorts();

        System.out.println("\nFound " + services.size() + " services with exposed ports:");
        services.forEach(service -> {
            System.out.println("\nContainer: " + service.containerName());
            System.out.println("  ID: " + service.containerId().substring(0, Math.min(12, service.containerId().length())));
            System.out.println("  Image: " + service.image());
            System.out.println("  Ports:");
            service.ports().forEach(port ->
                System.out.println("    " + port)
            );
        });
    }
}
