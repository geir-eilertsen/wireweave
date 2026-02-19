package com.wireweave.web.controller;

import com.wireweave.application.usecase.GenerateMeshConfigUseCase;
import com.wireweave.domain.model.WireGuardConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/mesh")
@RequiredArgsConstructor
public class MeshController {

    private final GenerateMeshConfigUseCase generateMeshConfigUseCase;

    @GetMapping("/configs")
    public ResponseEntity<Map<String, WireGuardConfig>> getAllConfigs() {
        Map<String, WireGuardConfig> configs = generateMeshConfigUseCase.execute();
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/configs/{peerName}/raw")
    public ResponseEntity<String> getConfigRaw(@PathVariable String peerName) {
        Map<String, WireGuardConfig> configs = generateMeshConfigUseCase.execute();
        WireGuardConfig config = configs.get(peerName);

        if (config == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header("Content-Type", "text/plain")
                .body(config.toConfigString());
    }
}
