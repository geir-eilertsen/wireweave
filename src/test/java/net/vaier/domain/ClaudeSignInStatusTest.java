package net.vaier.domain;

import net.vaier.domain.port.ForHoldingClaudeSignInStandings;
import net.vaier.domain.port.ForPublishingEvents;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * The question a fleet card's mark is gated on, answered here rather than in the browser.
     *
     * <p>Two of the six readings say where the sign-in actually stands. The other four say only that there
     * is no answer to be had — no CLI, no shell, no reply, or a reply Vaier could not read — and a surface
     * that turned any of them into a mark would be reporting a sign-in nobody observed. That is the same
     * failure {@code UNKNOWN} exists to prevent, so it is decided once, here.
     */
    @Test
    void saysWhetherItReportsWhereTheSignInStands_ratherThanLeavingACardToDecide() {
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, present()).saysWhereTheSignInStands()).isTrue();
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, absent()).saysWhereTheSignInStands()).isTrue();

        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, ClaudeSignInOutput.CLI_ABSENT_MARKER)
            .saysWhereTheSignInStands()).isFalse();
        assertThat(ClaudeSignInStatus.read(NAS, "nas", GEIR, "garbled").saysWhereTheSignInStands()).isFalse();
        assertThat(ClaudeSignInStatus.unreachable(NAS, "nas", GEIR).saysWhereTheSignInStands()).isFalse();
        assertThat(ClaudeSignInStatus.skipped(PHONE, "phone", null).saysWhereTheSignInStands()).isFalse();
    }

    // --- retaining a reading, so a fleet card can wear it ----------------------------------------------
    //
    // The five-minute sweep asks every machine it can already reach where it stands on Claude, and that
    // answer used to be read once, on one machine's own pane, and nowhere else. "Commit it, and wake the
    // fleet only if it moved" is a decision about standings, so it lives here rather than in the watcher.

    /** Records what it is handed and hands back what it replaced, like the real in-memory hold. */
    private static final class HeldStandings implements ForHoldingClaudeSignInStandings {
        private final Map<MachineId, ClaudeSignInStatus> held = new HashMap<>();

        @Override
        public Optional<ClaudeSignInStatus> record(ClaudeSignInStatus standing) {
            return Optional.ofNullable(held.put(standing.machineId(), standing));
        }

        @Override
        public List<ClaudeSignInStatus> getAll() {
            return List.copyOf(held.values());
        }

        @Override
        public void retainOnly(Set<MachineId> machineIds) {
            held.keySet().retainAll(machineIds);
        }
    }

    /** Counts what the fleet was told, which is the only thing these tests care about. */
    private static final class CountingPublisher implements ForPublishingEvents {
        private int published;

        @Override
        public void publish(String topic, String eventName, String data) {
            published++;
        }
    }

    @Test
    void retain_commitsTheReading_andWakesTheFleetOnce() {
        HeldStandings held = new HeldStandings();
        CountingPublisher publisher = new CountingPublisher();

        ClaudeSignInStatus.retain(ClaudeSignInStatus.read(NAS, "nas", GEIR, present()), held, publisher);

        assertThat(held.getAll()).singleElement()
            .satisfies(standing -> assertThat(standing.state()).isEqualTo(ClaudeSignInState.SIGNED_IN));
        assertThat(publisher.published).isEqualTo(1);
    }

    @Test
    void retain_readingTheSameStandingAgain_tellsTheFleetNothing() {
        HeldStandings held = new HeldStandings();
        CountingPublisher publisher = new CountingPublisher();
        ClaudeSignInStatus.retain(ClaudeSignInStatus.read(NAS, "nas", GEIR, present()), held, publisher);

        ClaudeSignInStatus.retain(ClaudeSignInStatus.read(NAS, "nas", GEIR, present()), held, publisher);

        assertThat(publisher.published).isEqualTo(1);
    }

    @Test
    void retain_aMachineThatSignedOut_wakesTheFleetAgain() {
        HeldStandings held = new HeldStandings();
        CountingPublisher publisher = new CountingPublisher();
        ClaudeSignInStatus.retain(ClaudeSignInStatus.read(NAS, "nas", GEIR, present()), held, publisher);

        ClaudeSignInStatus.retain(ClaudeSignInStatus.read(NAS, "nas", GEIR, absent()), held, publisher);

        assertThat(publisher.published).isEqualTo(2);
        assertThat(held.getAll()).singleElement()
            .satisfies(standing -> assertThat(standing.state()).isEqualTo(ClaudeSignInState.SIGNED_OUT));
    }

    /**
     * A machine that changed accounts without changing state. The account is half of what the card says on
     * hover, so a whole-value comparison is the point: anything an open Explorer is now getting wrong is a
     * reason to wake it, and nothing else is.
     */
    @Test
    void retain_aMachineNowSignedInAsSomebodyElse_wakesTheFleet() {
        HeldStandings held = new HeldStandings();
        CountingPublisher publisher = new CountingPublisher();
        ClaudeSignInStatus.retain(ClaudeSignInStatus.read(NAS, "nas", GEIR, present()), held, publisher);

        ClaudeSignInStatus.retain(new ClaudeSignInStatus(NAS, "nas", GEIR, ClaudeSignInState.SIGNED_IN,
            new ClaudeAccount("someone.else@example.com", "Example Org", "max")), held, publisher);

        assertThat(publisher.published).isEqualTo(2);
    }

    /**
     * A reading that never happened is not a standing. The sweep hands nothing here for a machine it never
     * asked, and nothing must be recorded — a card drawing a mark for a machine Vaier did not look at is
     * exactly the absence-read-as-health failure the disk mark was bitten by.
     */
    @Test
    void retain_nothingRead_recordsNothingAndSaysNothing() {
        HeldStandings held = new HeldStandings();
        CountingPublisher publisher = new CountingPublisher();

        ClaudeSignInStatus.retain(null, held, publisher);

        assertThat(held.getAll()).isEmpty();
        assertThat(publisher.published).isZero();
    }
}
