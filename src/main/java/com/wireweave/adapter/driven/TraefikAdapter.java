package com.wireweave.adapter.driven;

import com.wireweave.domain.ReverseProxyRoute;
import com.wireweave.domain.port.ForGettingReverseProxyRoutes;
import java.io.File;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TraefikAdapter implements ForGettingReverseProxyRoutes {

    private final Yaml yaml;

    public TraefikAdapter() {
        this.yaml = new Yaml();
    }

    /**
     * Extract routes from a Traefik YAML configuration file.
     * Parses the dynamic configuration and extracts router-service mappings with addresses and ports.
     *
     * @return List of TraefikRoute objects containing route information
     */
    public List<ReverseProxyRoute> getReverseProxyRoutes() {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        File configFile = new File("c://tmp/remote-apps.yml");

        try (FileInputStream inputStream = new FileInputStream(configFile)) {
            Map<String, Object> config = yaml.load(inputStream);

            if (config == null) {
                return routes;
            }

            // Extract HTTP routers
            Map<String, Object> http = getNestedMap(config, "http");
            if (http != null) {
                Map<String, Object> routers = getNestedMap(http, "routers");
                Map<String, Object> services = getNestedMap(http, "services");

                if (routers != null && services != null) {
                    routes.addAll(extractHttpRoutes(routers, services));
                }
            }

            // Extract TCP routers
            Map<String, Object> tcp = getNestedMap(config, "tcp");
            if (tcp != null) {
                Map<String, Object> routers = getNestedMap(tcp, "routers");
                Map<String, Object> services = getNestedMap(tcp, "services");

                if (routers != null && services != null) {
                    routes.addAll(extractTcpRoutes(routers, services));
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to read Traefik configuration file: " + configFile, e);
        }

        return routes;
    }

    private List<ReverseProxyRoute> extractHttpRoutes(Map<String, Object> routers, Map<String, Object> services) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        for (Map.Entry<String, Object> routerEntry : routers.entrySet()) {
            String routerName = routerEntry.getKey();
            Map<String, Object> routerConfig = castToMap(routerEntry.getValue());

            if (routerConfig != null) {
                String serviceName = (String) routerConfig.get("service");

                if (serviceName != null && services.containsKey(serviceName)) {
                    Map<String, Object> serviceConfig = castToMap(services.get(serviceName));

                    if (serviceConfig != null) {
                        routes.addAll(extractServiceUrls(routerName, serviceName, serviceConfig));
                    }
                }
            }
        }

        return routes;
    }

    private List<ReverseProxyRoute> extractTcpRoutes(Map<String, Object> routers, Map<String, Object> services) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        for (Map.Entry<String, Object> routerEntry : routers.entrySet()) {
            String routerName = routerEntry.getKey();
            Map<String, Object> routerConfig = castToMap(routerEntry.getValue());

            if (routerConfig != null) {
                String serviceName = (String) routerConfig.get("service");

                if (serviceName != null && services.containsKey(serviceName)) {
                    Map<String, Object> serviceConfig = castToMap(services.get(serviceName));

                    if (serviceConfig != null) {
                        routes.addAll(extractTcpServiceAddresses(routerName, serviceName, serviceConfig));
                    }
                }
            }
        }

        return routes;
    }

    private List<ReverseProxyRoute> extractServiceUrls(String routerName, String serviceName, Map<String, Object> serviceConfig) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        // Handle loadBalancer configuration
        Map<String, Object> loadBalancer = getNestedMap(serviceConfig, "loadBalancer");
        if (loadBalancer != null) {
            List<Map<String, Object>> servers = getNestedList(loadBalancer, "servers");

            if (servers != null) {
                for (Map<String, Object> server : servers) {
                    String url = (String) server.get("url");

                    if (url != null) {
                        AddressPort addressPort = parseUrl(url);
                        routes.add(new ReverseProxyRoute(routerName, addressPort.address, addressPort.port, serviceName));
                    }
                }
            }
        }

        return routes;
    }

    private List<ReverseProxyRoute> extractTcpServiceAddresses(String routerName, String serviceName, Map<String, Object> serviceConfig) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        // Handle loadBalancer configuration
        Map<String, Object> loadBalancer = getNestedMap(serviceConfig, "loadBalancer");
        if (loadBalancer != null) {
            List<Map<String, Object>> servers = getNestedList(loadBalancer, "servers");

            if (servers != null) {
                for (Map<String, Object> server : servers) {
                    String address = (String) server.get("address");

                    if (address != null) {
                        AddressPort addressPort = parseAddress(address);
                        routes.add(new ReverseProxyRoute(routerName, addressPort.address, addressPort.port, serviceName));
                    }
                }
            }
        }

        return routes;
    }

    /**
     * Parse URL in format: http://host:port or https://host:port
     */
    private AddressPort parseUrl(String url) {
        try {
            // Remove protocol
            String withoutProtocol = url.replaceFirst("^https?://", "");

            // Split host and port
            int colonIndex = withoutProtocol.lastIndexOf(':');
            if (colonIndex > 0) {
                String host = withoutProtocol.substring(0, colonIndex);
                int port = Integer.parseInt(withoutProtocol.substring(colonIndex + 1));
                return new AddressPort(host, port);
            }

            // Default ports if not specified
            if (url.startsWith("https://")) {
                return new AddressPort(withoutProtocol, 443);
            } else {
                return new AddressPort(withoutProtocol, 80);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse URL: " + url, e);
        }
    }

    /**
     * Parse address in format: host:port
     */
    private AddressPort parseAddress(String address) {
        try {
            int colonIndex = address.lastIndexOf(':');
            if (colonIndex > 0) {
                String host = address.substring(0, colonIndex);
                int port = Integer.parseInt(address.substring(colonIndex + 1));
                return new AddressPort(host, port);
            }
            throw new IllegalArgumentException("Address must be in format host:port");
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse address: " + address, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getNestedMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return castToMap(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getNestedList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return null;
    }

    private record AddressPort(String address, int port) {}

    public static void main(String[] args) {
        TraefikAdapter adapter = new TraefikAdapter();

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();

        System.out.println("\nFound " + routes.size() + " routes:");
        routes.forEach(route -> {
            System.out.println("\nRoute: " + route.getName());
            System.out.println("  Service: " + route.getService());
            System.out.println("  Address: " + route.getAddress() + ":" + route.getPort());
        });
    }
}
