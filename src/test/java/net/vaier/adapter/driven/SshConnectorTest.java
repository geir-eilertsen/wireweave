package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.SshTarget;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellCommandFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SshConnectorTest {

    private SshServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop(true);
    }

    private int startServer() throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setCommandFactory(ProcessShellCommandFactory.INSTANCE);
        server.setPasswordAuthenticator((u, p, s) -> "test".equals(u) && "secret".equals(p));
        server.start();
        return server.getPort();
    }

    // MINA's default SshClient kills a connection after 10 minutes of no traffic. A web-terminal PTY is
    // meant to sit idle for arbitrarily long (that's the whole point of the persistent shell), so Vaier's
    // own client must never be the one to end it.
    @Test
    void establishedClient_disablesIdleTimeout_soAnIdleShellIsNeverKilledByVaierItself() throws Exception {
        int port = startServer();
        SshTarget target = new SshTarget("127.0.0.1", port, "test", AuthMethod.PASSWORD, "secret", null, null);

        SshConnector.Connection connection = SshConnector.establish(target);
        try {
            assertThat(CoreModuleProperties.IDLE_TIMEOUT.getRequired(connection.client())).isZero();
        } finally {
            connection.close();
        }
    }

    // Periodic heartbeats keep the transport itself looking alive to anything in between (a WireGuard
    // tunnel, a home router's NAT table) that might otherwise silently drop an idle connection.
    @Test
    void establishedClient_sendsHeartbeats_soAnIdleTunnelNeverLooksDeadInBetween() throws Exception {
        int port = startServer();
        SshTarget target = new SshTarget("127.0.0.1", port, "test", AuthMethod.PASSWORD, "secret", null, null);

        SshConnector.Connection connection = SshConnector.establish(target);
        try {
            assertThat(CoreModuleProperties.HEARTBEAT_INTERVAL.getRequired(connection.client()))
                .isGreaterThan(Duration.ZERO);
        } finally {
            connection.close();
        }
    }
}
