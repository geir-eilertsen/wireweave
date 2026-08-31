package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.CancelClaudeSignInUseCase;
import net.vaier.application.GetClaudeSignInStatusUseCase;
import net.vaier.application.StartClaudeSignInUseCase;
import net.vaier.application.SignOutOfClaudeUseCase;
import net.vaier.application.SubmitClaudeSignInCodeUseCase;
import net.vaier.domain.ClaudeAccount;
import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.MachineId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's side of a <b>Claude sign-in</b>: where one machine stands, and the two steps that
 * sign it in — start (which hands back Anthropic's authorization URL to open) and code (which hands the
 * code Anthropic showed them to the CLI waiting on that machine).
 *
 * <p>These paths are non-whitelisted, so they sit under the Tier-3 admin auth chain automatically, like
 * every other management endpoint.
 *
 * <p><b>What crosses this boundary, and what never does.</b> Out goes Anthropic's authorization URL, on
 * its way to the operator's own browser. In comes the code Anthropic showed them, on its way to the
 * waiting CLI. Neither is stored, and neither is logged — the log lines here name a machine and nothing
 * else. The credential the CLI ends up with never comes near this controller at all: it is written by
 * the CLI on the machine, and Vaier only ever asks the CLI whether it is signed in.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ClaudeSignInController {

    private final GetClaudeSignInStatusUseCase getClaudeSignInStatusUseCase;
    private final StartClaudeSignInUseCase startClaudeSignInUseCase;
    private final SubmitClaudeSignInCodeUseCase submitClaudeSignInCodeUseCase;
    private final CancelClaudeSignInUseCase cancelClaudeSignInUseCase;
    private final SignOutOfClaudeUseCase signOutOfClaudeUseCase;

    /**
     * Where one machine — and the OS user Vaier acts as on it — stands. Deliberately per-machine: this is
     * drawn on that machine's own pane, and a fleet read would SSH to every machine in the fleet just to
     * paint one of them.
     */
    @GetMapping("/machines/{machineId}/claude-sign-in")
    public ResponseEntity<MachineResponse> status(@PathVariable String machineId) {
        return ResponseEntity.ok(MachineResponse.from(
            getClaudeSignInStatusUseCase.getClaudeSignInStatus(MachineId.of(machineId))));
    }

    /**
     * Start a sign-in on one machine and return the URL for the operator to open. They approve in their
     * own browser, on Anthropic's own pages, and come back with a code for {@link #submitCode}.
     */
    @PostMapping("/machines/{machineId}/claude-sign-in")
    public ResponseEntity<StartResponse> start(@PathVariable String machineId) {
        MachineId machine = MachineId.of(machineId);
        log.info("Starting a Claude sign-in on {}", machine);
        return ResponseEntity.ok(
            new StartResponse(startClaudeSignInUseCase.startClaudeSignIn(machine)));
    }

    /** Hand the waiting CLI the code Anthropic showed the operator, and report where the machine landed. */
    @PostMapping("/machines/{machineId}/claude-sign-in/code")
    public ResponseEntity<MachineResponse> submitCode(@PathVariable String machineId,
                                                      @RequestBody CodeRequest request) {
        MachineId machine = MachineId.of(machineId);
        return ResponseEntity.ok(MachineResponse.from(
            submitClaudeSignInCodeUseCase.submitClaudeSignInCode(machine, request.code())));
    }

    /**
     * Sign this machine's CLI out. Runs the CLI's own {@code auth logout} — Vaier never deletes a
     * credential — and answers with the machine's refreshed standing.
     */
    @PostMapping("/machines/{machineId}/claude-sign-out")
    public ResponseEntity<MachineResponse> signOut(@PathVariable String machineId) {
        MachineId machine = MachineId.of(machineId);
        log.info("Signing {} out of Claude", machine);
        return ResponseEntity.ok(MachineResponse.from(signOutOfClaudeUseCase.signOutOfClaude(machine)));
    }

    /** The operator closed the dialog. Ends the CLI left waiting at its prompt on that machine. */
    @DeleteMapping("/machines/{machineId}/claude-sign-in")
    public ResponseEntity<Void> cancel(@PathVariable String machineId) {
        cancelClaudeSignInUseCase.cancelClaudeSignIn(MachineId.of(machineId));
        return ResponseEntity.noContent().build();
    }

    /**
     * The code Anthropic showed the operator, on its way to the waiting CLI. It is written into that
     * process and is not kept — there is no field, no store and no log line that outlives this call.
     */
    record CodeRequest(String code) {}

    /** Anthropic's authorization URL, for the operator to open in their own browser. */
    record StartResponse(String authorizationUrl) {}

    /**
     * Where one <b>user on</b> one machine stands, and — when signed in and said so — which Anthropic
     * account as. {@code effectiveUsername} is the OS user the answer is about, and it is not optional
     * detail: a sign-in lives in one user's home, so a row that named only the machine let Vaier report
     * a healthy account while the one actually running that machine's work was expired. Every sentence
     * the browser builds from this — the row, Sign in, Sign out — has to name it.
     *
     * <p>{@code signInPossibleHere} and {@code signInCanBegin} are the domain's answers, not hints: whether
     * to draw this section at all, and whether to offer the verb. They are sent decided because the browser
     * deriving either from {@code state} is how the two came apart before — a signed-in machine was offered
     * no way to sign in again, though the domain has always allowed it.
     *
     * <p>The account fields are display-only observations read from {@code claude auth status --json};
     * they are never stored, and they carry no credential material because that command emits none.
     */
    record MachineResponse(String machineId, String machineName, String effectiveUsername, String state,
                           boolean signInPossibleHere, boolean signInCanBegin, String accountEmail,
                           String accountOrganisation, String subscriptionType) {
        static MachineResponse from(ClaudeSignInStatus status) {
            ClaudeAccount account = status.account();
            return new MachineResponse(status.machineId().value(), status.machineName(),
                status.effectiveUsername(), status.state().name(),
                status.signInIsPossibleHere(), status.signInCanBegin(),
                account == null ? null : account.email(),
                account == null ? null : account.organisation(),
                account == null ? null : account.subscriptionType());
        }
    }

}
