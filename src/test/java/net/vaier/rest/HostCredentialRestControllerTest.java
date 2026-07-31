package net.vaier.rest;

import net.vaier.application.DeleteHostCredentialUseCase;
import net.vaier.application.GenerateManagedKeypairUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetHostPublicKeyUseCase;
import net.vaier.application.SaveHostCredentialUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.HostCredential;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.MachineId;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class HostCredentialRestControllerTest {

    private static MachineId mid(String name) {
        return TestMachineIds.of(name);
    }

    @Mock SaveHostCredentialUseCase saveHostCredentialUseCase;
    @Mock GetHostCredentialUseCase getHostCredentialUseCase;
    @Mock DeleteHostCredentialUseCase deleteHostCredentialUseCase;
    @Mock GenerateManagedKeypairUseCase generateManagedKeypairUseCase;
    @Mock GetHostPublicKeyUseCase getHostPublicKeyUseCase;

    @InjectMocks HostCredentialRestController controller;

    @Test
    void put_savesCredentialBuiltFromPathAndBody_returnsRedactedView() {
        var request = new HostCredentialRestController.SaveCredentialRequest(
            "admin", "PASSWORD", "s3cret", null);

        ResponseEntity<HostCredentialRestController.CredentialResponse> response =
            controller.save(mid("nas").value(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().hasSecret()).isTrue();
        // The controller hands on the machine's name and the operator's draft; turning that pair into a
        // credential keyed by identity is the application's decision, not the controller's.
        ArgumentCaptor<net.vaier.domain.SshCredentialDraft> captor =
            ArgumentCaptor.forClass(net.vaier.domain.SshCredentialDraft.class);
        verify(saveHostCredentialUseCase).saveHostCredential(eq(mid("nas")), captor.capture());
        net.vaier.domain.SshCredentialDraft saved = captor.getValue();
        assertThat(saved.username()).isEqualTo("admin");
        assertThat(saved.authMethod()).isEqualTo(AuthMethod.PASSWORD);
        assertThat(saved.secret()).isEqualTo("s3cret");
    }

    @Test
    void put_invalidAuthMethod_propagatesIllegalArgument() {
        var request = new HostCredentialRestController.SaveCredentialRequest(
            "admin", "BANANA", "s3cret", null);

        assertThatThrownBy(() -> controller.save(mid("nas").value(), request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    /**
     * A blank secret is still rejected, just no longer by the controller: it assembles a draft and the
     * application turns that into the credential, where the domain's invariant lives. The controller's
     * job here is not to swallow it — the exception must reach GlobalExceptionHandler as a 400.
     */
    void put_blankSecret_propagatesIllegalArgumentFromTheUseCase() {
        var request = new HostCredentialRestController.SaveCredentialRequest(
            "admin", "PASSWORD", "  ", null);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("secret must not be blank"))
            .when(saveHostCredentialUseCase).saveHostCredential(eq(mid("nas")), any());

        assertThatThrownBy(() -> controller.save(mid("nas").value(), request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void get_present_returnsRedactedView() {
        when(getHostCredentialUseCase.getHostCredential(mid("nas")))
            .thenReturn(Optional.of(new HostCredentialView(mid("nas"), "admin", AuthMethod.PASSWORD, true, false)));

        ResponseEntity<HostCredentialRestController.CredentialResponse> response = controller.get(mid("nas").value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().username()).isEqualTo("admin");
        assertThat(response.getBody().hasSecret()).isTrue();
    }

    @Test
    void get_absent_returns404() {
        when(getHostCredentialUseCase.getHostCredential(mid("ghost"))).thenReturn(Optional.empty());

        assertThat(controller.get(mid("ghost").value()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_returns204() {
        ResponseEntity<Void> response = controller.delete(mid("nas").value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(deleteHostCredentialUseCase).deleteHostCredential(mid("nas"));
    }

    // --- managed keypairs (#309) ---

    @Test
    void generate_mintsAManagedKeypair_andReturnsThePublicKeyToInstall() {
        when(generateManagedKeypairUseCase.generateManagedKeypair(mid("nas"), "admin"))
            .thenReturn("ssh-ed25519 AAAA vaier");

        ResponseEntity<HostCredentialRestController.PublicKeyResponse> response = controller.generate(
            mid("nas").value(), new HostCredentialRestController.GenerateKeypairRequest("admin"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().publicKey()).isEqualTo("ssh-ed25519 AAAA vaier");
    }

    @Test
    void getPublicKey_present_returnsTheAuthorizedKeysLine() {
        when(getHostPublicKeyUseCase.getHostPublicKey(mid("nas")))
            .thenReturn(Optional.of("ssh-ed25519 AAAA vaier"));

        assertThat(controller.publicKey(mid("nas").value()).getBody().publicKey())
            .isEqualTo("ssh-ed25519 AAAA vaier");
    }

    @Test
    void getPublicKey_absent_returns404() {
        when(getHostPublicKeyUseCase.getHostPublicKey(mid("ghost"))).thenReturn(Optional.empty());

        assertThat(controller.publicKey(mid("ghost").value()).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_managedCredential_saysSo_soTheDialogCanHideThePrivateKeyField() {
        when(getHostCredentialUseCase.getHostCredential(mid("nas"))).thenReturn(
            Optional.of(new HostCredentialView(mid("nas"), "admin", AuthMethod.PRIVATE_KEY, true, true)));

        assertThat(controller.get(mid("nas").value()).getBody().managed()).isTrue();
    }

    @Test
    void generate_responseNeverCarriesThePrivateKey() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(generateManagedKeypairUseCase.generateManagedKeypair(mid("nas"), "admin"))
            .thenReturn("ssh-ed25519 AAAA vaier");

        mockMvc.perform(post("/machines/" + mid("nas") + "/ssh-credential/generate")
                .contentType("application/json")
                .content("{\"username\":\"admin\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicKey").value("ssh-ed25519 AAAA vaier"))
            .andExpect(content().string(not(containsString("PRIVATE KEY"))));
    }

    @Test
    void put_thenGet_responseBodyNeverCarriesSecretBytes() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(put("/machines/" + mid("nas") + "/ssh-credential")
                .contentType("application/json")
                .content("""
                    {"username":"admin","authMethod":"PRIVATE_KEY",
                     "secret":"-----BEGIN OPENSSH PRIVATE KEY-----secret-body-----END OPENSSH PRIVATE KEY-----","passphrase":"topsecretphrase"}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("secret-body"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("topsecretphrase"))))
            .andExpect(jsonPath("$.hasSecret").value(true));

        when(getHostCredentialUseCase.getHostCredential(mid("nas")))
            .thenReturn(Optional.of(new HostCredentialView(mid("nas"), "admin", AuthMethod.PRIVATE_KEY, true, false)));

        mockMvc.perform(get("/machines/" + mid("nas") + "/ssh-credential"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasSecret").value(true))
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("secret-body"))));
    }
}
