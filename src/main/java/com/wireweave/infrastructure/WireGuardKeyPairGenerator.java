package com.wireweave.infrastructure;

import com.wireweave.domain.port.KeyPairGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Component
@Slf4j
public class WireGuardKeyPairGenerator implements KeyPairGenerator {

    @Override
    public KeyPair generate() {
        try {
            // Generate private key
            Process privateKeyProcess = Runtime.getRuntime().exec("wg genkey");
            String privateKey = readProcessOutput(privateKeyProcess).trim();

            // Generate public key from private key
            Process publicKeyProcess = Runtime.getRuntime().exec("wg pubkey");
            publicKeyProcess.getOutputStream().write(privateKey.getBytes());
            publicKeyProcess.getOutputStream().close();
            String publicKey = readProcessOutput(publicKeyProcess).trim();

            log.debug("Generated WireGuard key pair");
            return new KeyPair(privateKey, publicKey);

        } catch (Exception e) {
            log.error("Failed to generate WireGuard key pair", e);
            throw new RuntimeException("Failed to generate WireGuard key pair", e);
        }
    }

    private String readProcessOutput(Process process) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            return output.toString();
        }
    }
}
