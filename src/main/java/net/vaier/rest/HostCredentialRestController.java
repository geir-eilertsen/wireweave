package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteHostCredentialUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.SaveHostCredentialUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.MachineId;
import net.vaier.domain.HostCredential;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.SshCredentialDraft;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin CRUD for the one host credential Vaier holds per machine (the credential vault, #307). These
 * paths are non-whitelisted, so they sit under the Tier-3 admin auth chain automatically. A GET only
 * ever returns the redacted {@link HostCredentialView}; secret and passphrase bytes never leave here.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class HostCredentialRestController {

    private final SaveHostCredentialUseCase saveHostCredentialUseCase;
    private final GetHostCredentialUseCase getHostCredentialUseCase;
    private final DeleteHostCredentialUseCase deleteHostCredentialUseCase;

    @PutMapping("/machines/{machineId}/ssh-credential")
    public ResponseEntity<CredentialResponse> save(@PathVariable String machineId,
                                                   @RequestBody SaveCredentialRequest request) {
        log.info("Saving SSH credential for machine {}", LogSafe.forLog(machineId));
        // An invalid authMethod, or a blank username/secret (validated in the domain), throws
        // IllegalArgumentException -> 400 via GlobalExceptionHandler; an unknown machine -> 404.
        SshCredentialDraft draft = new SshCredentialDraft(request.username(),
            AuthMethod.valueOf(request.authMethod()), request.secret(), request.passphrase());
        saveHostCredentialUseCase.saveHostCredential(MachineId.of(machineId), draft);
        return ResponseEntity.ok(new CredentialResponse(machineId, draft.username(),
            draft.authMethod().name(), draft.secret() != null && !draft.secret().isBlank()));
    }

    @GetMapping("/machines/{machineId}/ssh-credential")
    public ResponseEntity<CredentialResponse> get(@PathVariable String machineId) {
        return getHostCredentialUseCase.getHostCredential(MachineId.of(machineId))
            .map(view -> ResponseEntity.ok(CredentialResponse.from(machineId, view)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/machines/{machineId}/ssh-credential")
    public ResponseEntity<Void> delete(@PathVariable String machineId) {
        log.info("Deleting SSH credential for machine {}", LogSafe.forLog(machineId));
        deleteHostCredentialUseCase.deleteHostCredential(MachineId.of(machineId));
        return ResponseEntity.noContent().build();
    }

    record SaveCredentialRequest(String username, String authMethod, String secret, String passphrase) {}

    /**
     * The redacted response — reports presence of a secret, never the secret itself.
     *
     * <p>The machine is echoed from the caller's own path segment rather than read back off the stored
     * credential. Both are the same identity now, so this is only about not making the browser correlate a
     * reply with the request it answers.
     */
    record CredentialResponse(String machineId, String username, String authMethod, boolean hasSecret) {
        static CredentialResponse from(String machineId, HostCredentialView view) {
            return new CredentialResponse(machineId, view.username(),
                view.authMethod().name(), view.hasSecret());
        }
    }
}
