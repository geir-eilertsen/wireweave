package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteFleetCredentialUseCase;
import net.vaier.application.DistributeFleetCredentialUseCase;
import net.vaier.application.GetFleetCredentialStandingsUseCase;
import net.vaier.application.GetFleetCredentialsUseCase;
import net.vaier.application.SaveFleetCredentialUseCase;
import net.vaier.application.WithdrawFleetCredentialUseCase;
import net.vaier.domain.FleetCredential;
import net.vaier.domain.FleetCredentialStanding;
import net.vaier.domain.FleetCredentialView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin CRUD and distribution for <b>fleet credentials</b> — the operator secrets that must exist
 * identically <em>on</em> every machine that runs a shell, the mirror of the per-machine
 * {@link net.vaier.domain.HostCredential} Vaier uses to <em>reach</em> one. These paths are
 * non-whitelisted, so they sit under the Tier-3 admin auth chain automatically.
 *
 * <p>A GET only ever returns the redacted {@link FleetCredentialView} plus where the credential stands
 * on each machine; content bytes never leave here, and neither does a digest of them. The listing is
 * composed at the driving edge from two narrow use cases — the vault's own read, and the distributor's
 * last observation — because the vault's domain does not own the fleet and the distributor does not own
 * the vault.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class FleetCredentialController {

    private final SaveFleetCredentialUseCase saveFleetCredentialUseCase;
    private final GetFleetCredentialsUseCase getFleetCredentialsUseCase;
    private final DeleteFleetCredentialUseCase deleteFleetCredentialUseCase;
    private final DistributeFleetCredentialUseCase distributeFleetCredentialUseCase;
    private final WithdrawFleetCredentialUseCase withdrawFleetCredentialUseCase;
    private final GetFleetCredentialStandingsUseCase getFleetCredentialStandingsUseCase;

    @GetMapping("/fleet-credentials")
    public ResponseEntity<List<CredentialResponse>> list() {
        return ResponseEntity.ok(getFleetCredentialsUseCase.getFleetCredentials().stream()
            .map(view -> CredentialResponse.from(view,
                getFleetCredentialStandingsUseCase.getFleetCredentialStandings(view.name())))
            .toList());
    }

    /**
     * Store a fleet credential. The name comes from the path, never the body — a body that could name a
     * different credential is a body that could overwrite one. An unsafe name, path or mode is rejected
     * by the domain as {@code IllegalArgumentException} -> {@code 400}.
     *
     * <p>Storing is not distributing: nothing reaches a machine until the operator asks.
     */
    @PutMapping("/fleet-credentials/{name}")
    public ResponseEntity<CredentialResponse> save(@PathVariable String name,
                                                   @RequestBody SaveRequest request) {
        log.info("Saving fleet credential {}", LogSafe.forLog(name));
        FleetCredential credential =
            FleetCredential.of(name, request.targetPath(), request.mode(), request.content());
        saveFleetCredentialUseCase.saveFleetCredential(credential);
        // Echoed through the domain's own redaction rather than reassembled here: which fields are safe
        // to return is a decision FleetCredential already owns.
        return ResponseEntity.ok(CredentialResponse.from(credential.toView(),
            getFleetCredentialStandingsUseCase.getFleetCredentialStandings(name)));
    }

    /** Forget Vaier's copy. This reaches no machine — {@link #withdraw} is what revokes. */
    @DeleteMapping("/fleet-credentials/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        log.info("Deleting fleet credential {}", LogSafe.forLog(name));
        deleteFleetCredentialUseCase.deleteFleetCredential(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/fleet-credentials/{name}/distribute")
    public ResponseEntity<List<MachineStandingResponse>> distribute(@PathVariable String name) {
        return ResponseEntity.ok(MachineStandingResponse.from(
            distributeFleetCredentialUseCase.distributeFleetCredential(name)));
    }

    /** Revocation in one place: removes the credential's file from every machine it could have reached. */
    @PostMapping("/fleet-credentials/{name}/withdraw")
    public ResponseEntity<List<MachineStandingResponse>> withdraw(@PathVariable String name) {
        return ResponseEntity.ok(MachineStandingResponse.from(
            withdrawFleetCredentialUseCase.withdrawFleetCredential(name)));
    }

    /**
     * The secret is write-only over HTTP: it arrives here and is never sent back. {@code name} is
     * deliberately absent — the path segment names the credential.
     */
    record SaveRequest(String targetPath, String mode, String content) {}

    /** Where a credential stands on one machine. Identity first; the name is only a label to print. */
    record MachineStandingResponse(String machineId, String machineName, String state) {
        static List<MachineStandingResponse> from(List<FleetCredentialStanding> standings) {
            return standings.stream()
                .map(s -> new MachineStandingResponse(s.machineId().value(), s.machineName(),
                    s.state().name()))
                .toList();
        }
    }

    /** The redacted response — reports presence of a secret, never the secret and never its digest. */
    record CredentialResponse(String name, String targetPath, String mode, boolean hasSecret,
                              boolean distributed, List<MachineStandingResponse> machines) {
        static CredentialResponse from(FleetCredentialView view,
                                       List<FleetCredentialStanding> standings) {
            return new CredentialResponse(view.name(), view.targetPath(), view.mode(), view.hasSecret(),
                view.distributed(), MachineStandingResponse.from(standings));
        }
    }
}
