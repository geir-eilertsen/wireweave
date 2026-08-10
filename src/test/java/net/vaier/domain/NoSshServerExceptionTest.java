package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision "this connect failure means no SSH server is listening at all" lives here, in the domain,
 * not as a string match inside {@code SshConnector}. The negative cases carry as much weight as the
 * positive one: a timeout or an unreachable network misclassified as "no server" would send the operator
 * hunting for a package to install on a machine that is merely asleep or behind a routing problem.
 */
class NoSshServerExceptionTest {

    @Test
    void recognisesAConnectionRefused() {
        assertThat(NoSshServerException.isNoServerListening("Connection refused")).isTrue();
    }

    @Test
    void recognisesItRegardlessOfCase() {
        assertThat(NoSshServerException.isNoServerListening("CONNECTION REFUSED")).isTrue();
    }

    @Test
    void recognisesItEvenWhenTheJdkPrefixesOrSuffixesTheRawMessage() {
        // java.net.ConnectException's text varies slightly by platform/JDK, so this is a substring match.
        assertThat(NoSshServerException.isNoServerListening("Connection refused: connect")).isTrue();
        assertThat(NoSshServerException.isNoServerListening("connect(2) failed: Connection refused")).isTrue();
    }

    @Test
    void aTimeoutIsNotARefusal() {
        // A machine that is merely asleep, or slow behind a VPN, must not be told to install an SSH server.
        assertThat(NoSshServerException.isNoServerListening("Connection timed out")).isFalse();
        assertThat(NoSshServerException.isNoServerListening("connect timed out")).isFalse();
    }

    @Test
    void anUnreachableNetworkIsNotARefusal() {
        assertThat(NoSshServerException.isNoServerListening("No route to host")).isFalse();
        assertThat(NoSshServerException.isNoServerListening("Network is unreachable")).isFalse();
    }

    @Test
    void nothingToReadIsNotARefusal() {
        assertThat(NoSshServerException.isNoServerListening(null)).isFalse();
        assertThat(NoSshServerException.isNoServerListening("   ")).isFalse();
    }

    @Test
    void carriesTheMachineAndPortSoAHandlerCanNameThem() {
        NoSshServerException e = new NoSshServerException("192.168.3.104", 22);

        assertThat(e.machineName()).isEqualTo("192.168.3.104");
        assertThat(e.getMessage()).contains("192.168.3.104").contains("22");
    }

    @Test
    void theMessageCarriesTheRemedy_notJustTheDiagnosis() {
        // What to do about a machine with no SSH server at all is knowledge about the machine, not about
        // HTTP -- so it belongs in the domain's own sentence, where every driving adapter that reports this
        // failure (the Explorer, the web terminal) gets it, not just the one that happened to add it first.
        assertThat(new NoSshServerException("Roon kjøkken", 22).getMessage())
            .contains("Roon kjøkken")
            .contains("Install and start an SSH server");
    }
}
