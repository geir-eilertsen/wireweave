package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Where one machine stands on Claude sign-in, and what a fleet of those adds up to. */
class ClaudeSignInStatusTest {

    private static final MachineId NAS = TestMachineIds.of("nas");
    private static final MachineId PHONE = TestMachineIds.of("phone");
    private static final EffectiveUser GEIR = EffectiveUser.of("geir");

    @Test
    void readsAMachinesOwnReportIntoItsStandingAndAccount() {
        ClaudeSignInStatus status = ClaudeSignInStatus.read(NAS, "nas", GEIR, present());

        assertThat(status.state()).isEqualTo(ClaudeSignInState.SIGNED_IN);
        assertThat(status.account())
            .isEqualTo(new ClaudeAccount("operator@example.com", "Example Org", "max"));
        assertThat(status.machineId()).isEqualTo(NAS);
        assertThat(status.machineName()).isEqualTo("nas");
    }

    /** A phone has nowhere to run a shell. Skipped, never an error and never a failure. */
    @Test
    void aMachineVaierCannotOpenAShellOnIsSkipped() {
        ClaudeSignInStatus status = ClaudeSignInStatus.skipped(PHONE, "phone", null);

        assertThat(status.state()).isEqualTo(ClaudeSignInState.SKIPPED);
        assertThat(status.account()).isNull();
    }

    @Test
    void aMachineThatCouldNotBeReachedSaysSoRatherThanReportingSignedOut() {
        assertThat(ClaudeSignInStatus.unreachable(NAS, "nas", GEIR).state())
            .isEqualTo(ClaudeSignInState.UNREACHABLE);
    }

    /**
     * Which account a machine is signed in as rides along with its standing. Signing a fleet into the
     * wrong account is otherwise invisible until something fails at the far end of a workflow.
     */
    @Test
    void carriesTheAccountAMachineIsSignedInAs() {
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, present()).account().email())
            .isEqualTo("operator@example.com");
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, present()).account().subscriptionType())
            .isEqualTo("max");
    }

    /** No account to name is null, never a placeholder that could be mistaken for one. */
    @Test
    void carriesNoAccountForAMachineThatIsNotSignedIn() {
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, absent()).account()).isNull();
        assertThat(ClaudeSignInStatus.unreachable(NAS, "nas", GEIR).account()).isNull();
        assertThat(ClaudeSignInStatus.skipped(PHONE, "phone", null).account()).isNull();
    }

    private static String present() {
        return """
            {"loggedIn": true, "authMethod": "claude.ai", "email": "operator@example.com",
             "orgName": "Example Org", "subscriptionType": "max"}""";
    }

    private static String absent() {
        return "{\"loggedIn\": false, \"authMethod\": \"claude.ai\"}";
    }

    // --- A sign-in belongs to an OS user, not to a machine -------------------------------------------

    /**
     * <b>The defect this pins produced a confidently wrong readout on the live fleet.</b> Colina 27 showed
     * "Signed in as …, max" while the operator, working as {@code root} on that same box, was signed out
     * and expired. Both readings were true: Vaier had asked as {@code geir}, the user its host credential
     * logs in as. A Claude sign-in lives in one user's home directory, so it is a fact about a
     * <em>user on</em> a machine — and a status that names only the machine invites exactly that
     * mistake, with nothing in it from which an operator could spot the mismatch.
     *
     * <p>So the user is not optional decoration on this record. It is half of what the record identifies.
     */
    @Test
    void namesTheOsUserItsAnswerIsAbout() {
        ClaudeSignInStatus status = ClaudeSignInStatus.read(NAS, "nas", GEIR, present());

        assertThat(status.effectiveUser()).isEqualTo(GEIR);
        assertThat(status.effectiveUsername()).isEqualTo("geir");
    }

    /** Every way a standing can be produced names its user — not just the happy one. */
    @Test
    void everyKindOfStandingNamesItsUser() {
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, absent()).effectiveUsername())
            .isEqualTo("geir");
        assertThat(ClaudeSignInStatus.unreachable(NAS, "nas", GEIR).effectiveUsername())
            .isEqualTo("geir");
    }

    /**
     * A machine Vaier holds no credential for has no effective user, so there is no user to name — and
     * the status must say nothing rather than invent one. That is also the only case where it can happen:
     * no credential is precisely what makes a machine skipped.
     */
    @Test
    void namesNoUserForAMachineVaierHoldsNoLoginFor() {
        ClaudeSignInStatus skipped = ClaudeSignInStatus.skipped(PHONE, "phone", null);

        assertThat(skipped.effectiveUser()).isNull();
        assertThat(skipped.effectiveUsername()).isNull();
    }

    // --- Whether a sign-in can begin here -----------------------------------------------------------

    /**
     * One rule, decided once. {@code ClaudeSignIn.requireSignInCanBegin} refuses only a machine with no
     * CLI — an already-signed-in machine may sign in again, which is how an operator moves one onto a
     * different account or replaces a credential that has gone bad. The browser used to derive its own
     * version of this from the state string and got it wrong in exactly that case: a signed-in machine was
     * offered no way to sign in again. So the answer travels with the standing rather than being
     * re-derived from it.
     */
    @Test
    void saysWhetherASignInCanBeginRatherThanLeavingItToBeInferred() {
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, present()).signInCanBegin()).isTrue();
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, absent()).signInCanBegin()).isTrue();
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, "garbled").signInCanBegin()).isTrue();
        assertThat(ClaudeSignInStatus.unreachable(NAS, "nas", GEIR).signInCanBegin()).isTrue();
    }

    /** The two states where a sign-in has nowhere to happen. */
    @Test
    void saysASignInCannotBeginWhereThereIsNoCliAndNoShell() {
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, ClaudeSignInOutput.CLI_ABSENT_MARKER)
            .signInCanBegin()).isFalse();
        assertThat(ClaudeSignInStatus.skipped(PHONE, "phone", null).signInCanBegin()).isFalse();
    }

    /** Nowhere to sign in is also nowhere to draw a section about signing in. */
    @Test
    void saysWhetherASignInIsPossibleHereAtAll() {
        assertThat(ClaudeSignInStatus.skipped(PHONE, "phone", null).signInIsPossibleHere()).isFalse();
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, present()).signInIsPossibleHere()).isTrue();
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, ClaudeSignInOutput.CLI_ABSENT_MARKER)
            .signInIsPossibleHere()).isTrue();
    }
}
