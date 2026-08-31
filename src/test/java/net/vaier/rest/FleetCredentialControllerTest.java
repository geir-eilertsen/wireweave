package net.vaier.rest;

import net.vaier.application.DeleteFleetCredentialUseCase;
import net.vaier.application.DistributeFleetCredentialUseCase;
import net.vaier.application.GetFleetCredentialStandingsUseCase;
import net.vaier.application.GetFleetCredentialsUseCase;
import net.vaier.application.SaveFleetCredentialUseCase;
import net.vaier.application.WithdrawFleetCredentialUseCase;
import net.vaier.domain.FleetCredential;
import net.vaier.domain.FleetCredentialStanding;
import net.vaier.domain.FleetCredentialState;
import net.vaier.domain.FleetCredentialView;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FleetCredentialControllerTest {

    @Mock SaveFleetCredentialUseCase saveFleetCredentialUseCase;
    @Mock GetFleetCredentialsUseCase getFleetCredentialsUseCase;
    @Mock DeleteFleetCredentialUseCase deleteFleetCredentialUseCase;
    @Mock DistributeFleetCredentialUseCase distributeFleetCredentialUseCase;
    @Mock WithdrawFleetCredentialUseCase withdrawFleetCredentialUseCase;
    @Mock GetFleetCredentialStandingsUseCase getFleetCredentialStandingsUseCase;

    @InjectMocks FleetCredentialController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private static FleetCredentialStanding standing(String name, FleetCredentialState state) {
        return new FleetCredentialStanding(TestMachineIds.of(name), name, state);
    }

    @Test
    void get_listsCredentialsWithTheirPerMachineStateAndNeverASecret() throws Exception {
        when(getFleetCredentialsUseCase.getFleetCredentials()).thenReturn(List.of(
            new FleetCredentialView("claude-oauth", "~/.claude/.credentials.json", "0600", true, true)));
        when(getFleetCredentialStandingsUseCase.getFleetCredentialStandings("claude-oauth"))
            .thenReturn(List.of(standing("nas", FleetCredentialState.CURRENT),
                standing("phone", FleetCredentialState.SKIPPED)));

        mockMvc.perform(get("/fleet-credentials"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("claude-oauth"))
            .andExpect(jsonPath("$[0].targetPath").value("~/.claude/.credentials.json"))
            .andExpect(jsonPath("$[0].mode").value("0600"))
            .andExpect(jsonPath("$[0].hasSecret").value(true))
            .andExpect(jsonPath("$[0].distributed").value(true))
            .andExpect(jsonPath("$[0].machines[0].machineName").value("nas"))
            .andExpect(jsonPath("$[0].machines[0].state").value("CURRENT"))
            .andExpect(jsonPath("$[0].machines[1].state").value("SKIPPED"))
            .andExpect(content().string(not(containsString("content"))));
    }

    @Test
    void put_savesTheCredentialTheDomainBuilt() throws Exception {
        mockMvc.perform(put("/fleet-credentials/claude-oauth").contentType(APPLICATION_JSON)
                .content("{\"targetPath\":\"~/.claude/.credentials.json\",\"mode\":\"0600\","
                    + "\"content\":\"{}\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("claude-oauth"))
            .andExpect(jsonPath("$.hasSecret").value(true));

        ArgumentCaptor<FleetCredential> saved = ArgumentCaptor.forClass(FleetCredential.class);
        verify(saveFleetCredentialUseCase).saveFleetCredential(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("claude-oauth");
        assertThat(saved.getValue().targetPath()).isEqualTo("~/.claude/.credentials.json");
        assertThat(saved.getValue().content()).isEqualTo("{}");
    }

    @Test
    void put_neverEchoesTheSecretBack() throws Exception {
        mockMvc.perform(put("/fleet-credentials/claude-oauth").contentType(APPLICATION_JSON)
                .content("{\"targetPath\":\"/etc/vaier/t\",\"mode\":\"0600\","
                    + "\"content\":\"totally-secret-value\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("totally-secret-value"))));
    }

    @Test
    void put_takesTheNameFromThePathSoTheBodyCannotFileItSomewhereElse() throws Exception {
        mockMvc.perform(put("/fleet-credentials/claude-oauth").contentType(APPLICATION_JSON)
                .content("{\"name\":\"something-else\",\"targetPath\":\"/etc/vaier/t\","
                    + "\"content\":\"x\"}"))
            .andExpect(status().isOk());

        ArgumentCaptor<FleetCredential> saved = ArgumentCaptor.forClass(FleetCredential.class);
        verify(saveFleetCredentialUseCase).saveFleetCredential(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("claude-oauth");
    }

    @Test
    void put_defaultsTheModeWhenTheOperatorGivesNone() throws Exception {
        mockMvc.perform(put("/fleet-credentials/claude-oauth").contentType(APPLICATION_JSON)
                .content("{\"targetPath\":\"/etc/vaier/t\",\"content\":\"x\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("0600"));
    }

    @Test
    void put_rejectsAnUnsafePathAsABadRequest() throws Exception {
        mockMvc.perform(put("/fleet-credentials/claude-oauth").contentType(APPLICATION_JSON)
                .content("{\"targetPath\":\"/etc/$(whoami)\",\"content\":\"x\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void put_rejectsAnUnsafeNameAsABadRequest() throws Exception {
        // The name is a shell/URL token; anything outside [A-Za-z0-9_-] never reaches the vault.
        mockMvc.perform(put("/fleet-credentials/bad.name").contentType(APPLICATION_JSON)
                .content("{\"targetPath\":\"/etc/vaier/t\",\"content\":\"x\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void delete_forgetsVaiersOwnCopy() throws Exception {
        mockMvc.perform(delete("/fleet-credentials/claude-oauth"))
            .andExpect(status().isNoContent());

        verify(deleteFleetCredentialUseCase).deleteFleetCredential("claude-oauth");
    }

    @Test
    void distribute_reportsWhereTheCredentialNowStandsOnEachMachine() throws Exception {
        when(distributeFleetCredentialUseCase.distributeFleetCredential("claude-oauth"))
            .thenReturn(List.of(standing("nas", FleetCredentialState.CURRENT),
                standing("nuc", FleetCredentialState.FAILED)));

        mockMvc.perform(post("/fleet-credentials/claude-oauth/distribute"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].machineName").value("nas"))
            .andExpect(jsonPath("$[0].state").value("CURRENT"))
            .andExpect(jsonPath("$[1].state").value("FAILED"));
    }

    @Test
    void withdraw_reportsWhereTheCredentialNowStandsOnEachMachine() throws Exception {
        when(withdrawFleetCredentialUseCase.withdrawFleetCredential("claude-oauth"))
            .thenReturn(List.of(standing("nas", FleetCredentialState.WITHDRAWN)));

        mockMvc.perform(post("/fleet-credentials/claude-oauth/withdraw"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].state").value("WITHDRAWN"));

        verify(withdrawFleetCredentialUseCase).withdrawFleetCredential("claude-oauth");
    }
}
