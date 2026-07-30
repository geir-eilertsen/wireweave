package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision "this SSH failure means the machine has no SFTP subsystem" lives here, in the domain, and
 * not as a string match inside the SFTP adapter. The negative cases carry as much weight as the positive
 * one: an ordinary connect failure misclassified as a missing subsystem would send the operator off to
 * install a package on a machine that is merely asleep.
 */
class NoSftpSubsystemExceptionTest {

    @Test
    void recognisesMinasChannelDiedDuringSubsystemInitSignature() {
        assertThat(NoSftpSubsystemException.isSubsystemRefusal("Closing while await init message")).isTrue();
    }

    @Test
    void recognisesTheSignatureEvenWhenMinaPrefixesItWithTheChannel() {
        // MINA has carried this text both bare and prefixed with the channel it happened on, so the
        // recognition is a substring match rather than an equality one.
        assertThat(NoSftpSubsystemException.isSubsystemRefusal(
            "SftpChannelSubsystem[id=0] Closing while await init message")).isTrue();
    }

    @Test
    void recognisesTheSignatureRegardlessOfCase() {
        assertThat(NoSftpSubsystemException.isSubsystemRefusal("closing while AWAIT INIT message")).isTrue();
    }

    @Test
    void anUnreachableMachineIsNotAMissingSubsystem() {
        assertThat(NoSftpSubsystemException.isSubsystemRefusal("Connection refused")).isFalse();
        assertThat(NoSftpSubsystemException.isSubsystemRefusal("No route to host")).isFalse();
        assertThat(NoSftpSubsystemException.isSubsystemRefusal(
            "Timeout after 15000 ms. waiting for org.apache.sshd.client.future.DefaultConnectFuture")).isFalse();
    }

    @Test
    void arejectedLoginIsNotAMissingSubsystem() {
        assertThat(NoSftpSubsystemException.isSubsystemRefusal("Authentication failed for geir@10.13.13.6"))
            .isFalse();
    }

    @Test
    void nothingToReadIsNotAMissingSubsystem() {
        assertThat(NoSftpSubsystemException.isSubsystemRefusal(null)).isFalse();
        assertThat(NoSftpSubsystemException.isSubsystemRefusal("   ")).isFalse();
    }

    @Test
    void carriesTheMachineSoAHandlerCanNameIt() {
        NoSftpSubsystemException e = new NoSftpSubsystemException("192.168.3.106");

        assertThat(e.machineName()).isEqualTo("192.168.3.106");
        assertThat(e.getMessage()).contains("192.168.3.106").contains("SFTP");
    }

    @Test
    void theMessageCarriesTheRemedy_notJustTheDiagnosis() {
        // What to do about a machine with no SFTP subsystem is knowledge about the machine, not about HTTP —
        // so it belongs in the domain's own sentence, where every driving adapter that reports this failure
        // gets it. Left to the REST handler alone, the terminal socket or a future CLI would quietly lose it.
        assertThat(new NoSftpSubsystemException("Roon loftstue").getMessage())
            .contains("openssh-sftp-server")
            .contains("OpenSSH");
    }
}
