package com.wireweave.domain.port;

import com.wireweave.domain.model.MeshTopology;
import com.wireweave.domain.model.Peer;
import com.wireweave.domain.model.WireGuardConfig;

public interface ConfigurationGenerator {

    WireGuardConfig generateConfig(Peer peer, MeshTopology topology);
}
