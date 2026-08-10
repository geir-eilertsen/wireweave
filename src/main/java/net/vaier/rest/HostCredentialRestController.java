package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteHostCredentialUseCase;
import net.vaier.application.GenerateManagedKeypairUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetHostPublicKeyUseCase;
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
import org.springframework.web.bind.annotation.PostMapping;
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
    private final GenerateManagedKeypairUseCase generateManagedKeypairUseCase;
    private final GetHostPublicKeyUseCase getHostPublicKeyUseCase;

    @PutMapping("/machines/{machineId}/ssh-credential")
    public ResponseEntity<CredentialResponse> save(@PathVariable String machineId,
                                                   @RequestBody SaveCredentialRequest request) {
        log.info("Saving SSH credential for machine {}", LogSafe.forLog(machineId));
        // An invalid authMethod, or a blank username/secret (validated in the domain), throws
        // IllegalArgumentException -> 400 via GlobalExceptionHandler; an unknown machine -> 404.
        MachineId id = MachineId.of(machineId);
        SshCredentialDraft draft = new SshCredentialDraft(request.username(),
            AuthMethod.valueOf(request.authMethod()), request.secret(), request.passphrase());
        saveHostCredentialUseCase.saveHostCredential(id, draft);
        // Echoed through the domain's own redaction rather than reassembled here: which fields are safe to
        // return, and the fact that a pasted credential is never a managed keypair, are decisions
        // HostCredential already owns. Restating them in the controller would be a second, unenforced copy.
        return ResponseEntity.ok(CredentialResponse.from(machineId, draft.forMachine(id).toView()));
    }

    @GetMapping("/machines/{machineId}/ssh-credential")
    public ResponseEntity<CredentialResponse> get(@PathVariable String machineId) {
        return getHostCredentialUseCase.getHostCredential(MachineId.of(machineId))
            .map(view -> ResponseEntity.ok(CredentialResponse.from(machineId, view)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Mint a managed keypair for this machine (#309). Destructive — it replaces whatever login Vaier held
     * — so the browser confirms with the operator first; the reply is the public key to install on the
     * host, and only ever the public key.
     */
    @PostMapping("/machines/{machineId}/ssh-credential/generate")
    public ResponseEntity<PublicKeyResponse> generate(@PathVariable String machineId,
                                                      @RequestBody GenerateKeypairRequest request) {
        log.info("Generating a managed keypair for machine {}", LogSafe.forLog(machineId));
        return ResponseEntity.ok(new PublicKeyResponse(
            generateManagedKeypairUseCase.generateManagedKeypair(MachineId.of(machineId), request.username())));
    }

    /** The public half of this machine's key credential — safe to return; it is not a secret. */
    @GetMapping("/machines/{machineId}/ssh-credential/public-key")
    public ResponseEntity<PublicKeyResponse> publicKey(@PathVariable String machineId) {
        return getHostPublicKeyUseCase.getHostPublicKey(MachineId.of(machineId))
            .map(key -> ResponseEntity.ok(new PublicKeyResponse(key)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/machines/{machineId}/ssh-credential")
    public ResponseEntity<Void> delete(@PathVariable String machineId) {
        log.info("Deleting SSH credential for machine {}", LogSafe.forLog(machineId));
        deleteHostCredentialUseCase.deleteHostCredential(MachineId.of(machineId));
        return ResponseEntity.noContent().build();
    }

    record SaveCredentialRequest(String username, String authMethod, String secret, String passphrase) {}

    /** Only the login name: everything else about a managed keypair is Vaier's decision, not the caller's. */
    record GenerateKeypairRequest(String username) {}

    /** The {@code authorized_keys} line. Never accompanied by the private half. */
    record PublicKeyResponse(String publicKey) {}

    /**
     * The redacted response — reports presence of a secret, never the secret itself.
     *
     * <p>The machine is echoed from the caller's own path segment rather than read back off the stored
     * credential. Both are the same identity now, so this is only about not making the browser correlate a
     * reply with the request it answers.
     */
    record CredentialResponse(String machineId, String username, String authMethod, boolean hasSecret,
                              boolean managed) {
        static CredentialResponse from(String machineId, HostCredentialView view) {
            return new CredentialResponse(machineId, view.username(),
                view.authMethod().name(), view.hasSecret(), view.managed());
        }
    }
}
