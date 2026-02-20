package com.wireweave.adapter.driven;

import com.wireweave.domain.WireguardConfig;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WireguardAdapter {

    /**
     * Parse a WireGuard configuration file.
     *
     * @param configPath Path to the WireGuard configuration file
     * @return WireguardConfig object containing interface and peer information
     */
    public WireguardConfig parseConfig(String configPath) {
        File configFile = new File(configPath);

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            WireguardConfig.WireguardInterface interfaceConfig = null;
            List<WireguardConfig.WireguardPeer> peers = new ArrayList<>();

            String currentSection = null;
            String address = null;
            Integer listenPort = null;
            String privateKeyPath = null;
            List<String> postUpCommands = new ArrayList<>();
            List<String> postDownCommands = new ArrayList<>();

            String peerComment = null;
            String publicKey = null;
            String allowedIPs = null;
            String endpoint = null;
            Integer persistentKeepalive = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines
                if (line.isEmpty()) {
                    continue;
                }

                // Check for section headers
                if (line.equals("[Interface]")) {
                    currentSection = "Interface";
                    continue;
                } else if (line.equals("[Peer]")) {
                    // Save previous peer if exists
                    if (currentSection != null && currentSection.equals("Peer") && publicKey != null) {
                        peers.add(new WireguardConfig.WireguardPeer(
                            peerComment != null ? peerComment : "unknown",
                            publicKey,
                            allowedIPs,
                            endpoint,
                            persistentKeepalive
                        ));
                    }

                    // Start new peer
                    currentSection = "Peer";
                    peerComment = null;
                    publicKey = null;
                    allowedIPs = null;
                    endpoint = null;
                    persistentKeepalive = null;
                    continue;
                }

                // Handle comments (peer names)
                if (line.startsWith("#")) {
                    String comment = line.substring(1).trim();
                    if (currentSection != null && currentSection.equals("Peer") && !comment.isEmpty()) {
                        peerComment = comment;
                    }
                    continue;
                }

                // Parse key-value pairs
                String[] parts = line.split("=", 2);
                if (parts.length != 2) {
                    continue;
                }

                String key = parts[0].trim();
                String value = parts[1].trim();

                if (currentSection != null && currentSection.equals("Interface")) {
                    switch (key) {
                        case "Address":
                            address = value;
                            break;
                        case "ListenPort":
                            listenPort = Integer.parseInt(value);
                            break;
                        case "PostUp":
                            postUpCommands.add(value);
                            // Extract private key path if present
                            if (value.contains("private-key")) {
                                privateKeyPath = extractPrivateKeyPath(value);
                            }
                            break;
                        case "PostDown":
                            postDownCommands.add(value);
                            break;
                    }
                } else if (currentSection != null && currentSection.equals("Peer")) {
                    switch (key) {
                        case "PublicKey":
                            publicKey = value;
                            break;
                        case "AllowedIPs":
                            allowedIPs = value;
                            break;
                        case "Endpoint":
                            endpoint = value;
                            break;
                        case "PersistentKeepalive":
                            persistentKeepalive = Integer.parseInt(value);
                            break;
                    }
                }
            }

            // Save last peer if exists
            if (currentSection != null && currentSection.equals("Peer") && publicKey != null) {
                peers.add(new WireguardConfig.WireguardPeer(
                    peerComment != null ? peerComment : "unknown",
                    publicKey,
                    allowedIPs,
                    endpoint,
                    persistentKeepalive
                ));
            }

            // Create interface config
            interfaceConfig = new WireguardConfig.WireguardInterface(
                address,
                listenPort,
                privateKeyPath,
                postUpCommands,
                postDownCommands
            );

            return new WireguardConfig(interfaceConfig, peers);

        } catch (IOException e) {
            throw new RuntimeException("Failed to read WireGuard configuration file: " + configPath, e);
        }
    }

    /**
     * Extract private key path from PostUp command.
     * Example: "wg set %i private-key /etc/wireguard/%i.key" -> "/etc/wireguard/%i.key"
     */
    private String extractPrivateKeyPath(String postUpCommand) {
        Pattern pattern = Pattern.compile("private-key\\s+(\\S+)");
        Matcher matcher = pattern.matcher(postUpCommand);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: WireguardAdapter <path-to-wireguard-config>");
            System.exit(1);
        }

        WireguardAdapter adapter = new WireguardAdapter();
        String configPath = args[0];

        System.out.println("Reading WireGuard configuration from: " + configPath);

        WireguardConfig config = adapter.parseConfig(configPath);

        System.out.println("\n=== Interface Configuration ===");
        WireguardConfig.WireguardInterface iface = config.getInterfaceConfig();
        System.out.println("Address: " + iface.getAddress());
        System.out.println("ListenPort: " + iface.getListenPort());
        System.out.println("PrivateKeyPath: " + iface.getPrivateKeyPath());
        System.out.println("PostUp Commands: " + iface.getPostUpCommands().size());
        System.out.println("PostDown Commands: " + iface.getPostDownCommands().size());

        System.out.println("\n=== Peers (" + config.getPeers().size() + ") ===");
        for (WireguardConfig.WireguardPeer peer : config.getPeers()) {
            System.out.println("\nPeer: " + peer.getName());
            System.out.println("  PublicKey: " + peer.getPublicKey());
            System.out.println("  AllowedIPs: " + peer.getAllowedIPs());
            if (peer.getEndpoint() != null) {
                System.out.println("  Endpoint: " + peer.getEndpoint());
            }
            if (peer.getPersistentKeepalive() != null) {
                System.out.println("  PersistentKeepalive: " + peer.getPersistentKeepalive());
            }
        }
    }
}
