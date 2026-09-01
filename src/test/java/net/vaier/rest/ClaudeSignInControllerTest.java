package net.vaier.rest;

import net.vaier.adapter.driven.InMemoryClaudeSignInStandingCache;
import net.vaier.application.CancelClaudeSignInUseCase;
import net.vaier.application.GetClaudeSignInStatusUseCase;
import net.vaier.application.StartClaudeSignInUseCase;
import net.vaier.application.SignOutOfClaudeUseCase;
import net.vaier.application.SubmitClaudeSignInCodeUseCase;
import net.vaier.domain.ClaudeSignIn;
import net.vaier.domain.ClaudeSignInFailedException;
import net.vaier.domain.ClaudeSignInState;
import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.EffectiveUser;
import net.vaier.domain.MachineId;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.port.ForPublishingEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ClaudeSignInControllerTest {

    private static final MachineId NAS = TestMachineIds.of("nas");
    private static final MachineId PHONE = TestMachineIds.of("phone");
    private static final String URL = "https://claude.com/cai/oauth/authorize?code=true&state=xyz";
    private static final String SIGNED_IN_JSON = """
        {"loggedIn": true, "authMethod": "claude.ai", "email": "operator@example.com",
         "orgName": "Example Org", "subscriptionType": "max"}""";

    @Mock GetClaudeSignInStatusUseCase getClaudeSignInStatusUseCase;
    @Mock StartClaudeSignInUseCase startClaudeSignInUseCase;
    @Mock SubmitClaudeSignInCodeUseCase submitClaudeSignInCodeUseCase;
    @Mock CancelClaudeSignInUseCase cancelClaudeSignInUseCase;
    @Mock SignOutOfClaudeUseCase signOutOfClaudeUseCase;
    @Mock ForPublishingEvents events;

    // The real store, so these tests assert what the fleet endpoint would actually serve next.
    private final InMemoryClaudeSignInStandingCache standings = new InMemoryClaudeSignInStandingCache();

    private ClaudeSignInController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ClaudeSignInController(getClaudeSignInStatusUseCase, startClaudeSignInUseCase,
            submitClaudeSignInCodeUseCase, cancelClaudeSignInUseCase, signOutOfClaudeUseCase,
            standings, events);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    /**
     * One machine's standing, drawn on that machine's own pane — not a fleet read. Naming the OS user is
     * the part that must not slip: the same answer is true for {@code geir} and false for {@code root} on
     * the same box.
     */
    @Test
    void reportsWhereOneMachineStandsAndWhichUserItIsAbout() throws Exception {
        when(getClaudeSignInStatusUseCase.getClaudeSignInStatus(NAS)).thenReturn(
            ClaudeSignInStatus.read(NAS, "nas", EffectiveUser.of("geir"), SIGNED_IN_JSON));

        mockMvc.perform(get("/machines/" + NAS.value() + "/claude-sign-in"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.machineId").value(NAS.value()))
            .andExpect(jsonPath("$.machineName").value("nas"))
            .andExpect(jsonPath("$.effectiveUsername").value("geir"))
            .andExpect(jsonPath("$.state").value("SIGNED_IN"))
            .andExpect(jsonPath("$.accountEmail").value("operator@example.com"))
            .andExpect(jsonPath("$.subscriptionType").value("max"));
    }

    /** A machine with nowhere to sign in answers plainly, carrying no user it does not have. */
    @Test
    void reportsASkippedMachineWithNoUserToName() throws Exception {
        when(getClaudeSignInStatusUseCase.getClaudeSignInStatus(PHONE))
            .thenReturn(ClaudeSignInStatus.skipped(PHONE, "phone", null));

        mockMvc.perform(get("/machines/" + PHONE.value() + "/claude-sign-in"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("SKIPPED"))
            .andExpect(jsonPath("$.effectiveUsername").doesNotExist());
    }

    @Test
    void startingASignInReturnsAnthropicsAuthorizationUrlForTheOperatorToOpen() throws Exception {
        when(startClaudeSignInUseCase.startClaudeSignIn(NAS)).thenReturn(URL);

        mockMvc.perform(post("/machines/" + NAS.value() + "/claude-sign-in"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authorizationUrl").value(URL));
    }

    @Test
    void submittingTheCodeReportsWhereTheMachineEndedUp() throws Exception {
        when(submitClaudeSignInCodeUseCase.submitClaudeSignInCode(NAS, "abc123#xyz")).thenReturn(
            ClaudeSignInStatus.read(NAS, "nas", EffectiveUser.of("geir"), SIGNED_IN_JSON));

        mockMvc.perform(post("/machines/" + NAS.value() + "/claude-sign-in/code")
                .contentType(APPLICATION_JSON).content("{\"code\":\"abc123#xyz\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("SIGNED_IN"));
    }

    @Test
    void abandoningASignInEndsIt() throws Exception {
        mockMvc.perform(delete("/machines/" + NAS.value() + "/claude-sign-in"))
            .andExpect(status().isNoContent());

        verify(cancelClaudeSignInUseCase).cancelClaudeSignIn(NAS);
    }

    /**
     * A path segment is an identity. A stale name in a bookmarked URL must never start a sign-in on
     * whatever machine now bears it.
     */
    @Test
    void refusesAPathSegmentThatIsNotAMachineIdentity() throws Exception {
        mockMvc.perform(post("/machines/nas/claude-sign-in"))
            .andExpect(status().isBadRequest());
    }

    /**
     * A code that is missing or is not a plain authorization code is the domain's call, not the
     * controller's — {@code ClaudeSignIn.keystrokesForCode} refuses it, and that surfaces as a 400. The
     * controller does not re-decide it here; it just does not swallow it.
     */
    @Test
    void surfacesTheDomainsRejectionOfABadCodeAsABadRequest() throws Exception {
        when(submitClaudeSignInCodeUseCase.submitClaudeSignInCode(eq(NAS), any()))
            .thenThrow(new IllegalArgumentException("The authorization code must not be blank"));

        mockMvc.perform(post("/machines/" + NAS.value() + "/claude-sign-in/code")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }

    /**
     * The whole contract of a failed start is an honest message with a way forward, so it must reach the
     * operator intact rather than being collapsed into a generic 500 the way an unmapped exception is.
     */
    @Test
    void surfacesAFailedStartWithItsMessageIntact() throws Exception {
        when(startClaudeSignInUseCase.startClaudeSignIn(NAS)).thenThrow(new ClaudeSignInFailedException(
            "Vaier could not read the login URL from the Claude CLI's output — open a terminal instead."));

        mockMvc.perform(post("/machines/" + NAS.value() + "/claude-sign-in"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("CLAUDE_SIGN_IN_FAILED"))
            .andExpect(jsonPath("$.message").value(containsString("could not read the login URL")));
    }

    /**
     * Sign-out is its own endpoint and answers with the machine's refreshed standing, so the operator sees
     * what actually happened rather than being told it worked.
     */
    @Test
    void signingOutReportsTheMachinesRefreshedStanding() throws Exception {
        when(signOutOfClaudeUseCase.signOutOfClaude(NAS)).thenReturn(
            ClaudeSignInStatus.read(NAS, "nas", EffectiveUser.of("geir"), "{\"loggedIn\": false}"));

        mockMvc.perform(post("/machines/" + NAS.value() + "/claude-sign-out"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("SIGNED_OUT"))
            .andExpect(jsonPath("$.effectiveUsername").value("geir"))
            .andExpect(jsonPath("$.accountEmail").doesNotExist());
    }

    @Test
    void refusesASignOutPathSegmentThatIsNotAMachineIdentity() throws Exception {
        mockMvc.perform(post("/machines/nas/claude-sign-out"))
            .andExpect(status().isBadRequest());
    }

    // --- every reading is a standing ------------------------------------------------------------------
    //
    // The sweep is not the only place a machine's sign-in is read. It used to be the only place a reading
    // was kept, so an operator who signed a machine in watched its fleet card stay signed-out for up to
    // five minutes — the answer was on screen in the pane and thrown away. The disk sibling already retains
    // from every reading; these three do the same, through the one domain method that decides it.

    /**
     * The payoff. A sign-in the operator just completed is what the fleet is served next, and an open
     * Explorer is woken to come and get it.
     */
    @Test
    void completingASignIn_isTheFleetsStandingAtOnce_andWakesAnOpenExplorer() throws Exception {
        when(submitClaudeSignInCodeUseCase.submitClaudeSignInCode(NAS, "abc123#xyz")).thenReturn(
            ClaudeSignInStatus.read(NAS, "nas", EffectiveUser.of("geir"), SIGNED_IN_JSON));

        mockMvc.perform(post("/machines/" + NAS.value() + "/claude-sign-in/code")
                .contentType(APPLICATION_JSON).content("{\"code\":\"abc123#xyz\"}"))
            .andExpect(status().isOk());

        assertThat(standings.getAll()).singleElement()
            .returns(ClaudeSignInState.SIGNED_IN, ClaudeSignInStatus::state);
        verify(events).publish("vpn-peers", "claude-standing-changed", "");
    }

    /** Signing out is a reading too — the card must not keep saying signed in until the next sweep. */
    @Test
    void signingOut_isTheFleetsStandingAtOnce() throws Exception {
        when(signOutOfClaudeUseCase.signOutOfClaude(NAS)).thenReturn(
            ClaudeSignInStatus.read(NAS, "nas", EffectiveUser.of("geir"), "{\"loggedIn\": false}"));

        mockMvc.perform(post("/machines/" + NAS.value() + "/claude-sign-out"))
            .andExpect(status().isOk());

        assertThat(standings.getAll()).singleElement()
            .returns(ClaudeSignInState.SIGNED_OUT, ClaudeSignInStatus::state);
        verify(events).publish("vpn-peers", "claude-standing-changed", "");
    }

    /**
     * Opening a machine's pane is the reading that fixes a standing the sweep took before something moved
     * outside Vaier — someone signing in over SSH themselves, say.
     */
    @Test
    void lookingAtOneMachine_retainsWhatThatLookRead() throws Exception {
        when(getClaudeSignInStatusUseCase.getClaudeSignInStatus(NAS)).thenReturn(
            ClaudeSignInStatus.read(NAS, "nas", EffectiveUser.of("geir"), SIGNED_IN_JSON));

        mockMvc.perform(get("/machines/" + NAS.value() + "/claude-sign-in"))
            .andExpect(status().isOk());

        assertThat(standings.getAll()).singleElement()
            .returns(ClaudeSignInState.SIGNED_IN, ClaudeSignInStatus::state);
    }

    /**
     * A first reading always speaks; a second look at a machine that has not moved says nothing more. The
     * domain gates that, not the controller — which is the whole reason the controller calls it.
     */
    @Test
    void aSecondLookAtAMachineThatHasNotMoved_wakesNobodyAgain() throws Exception {
        when(getClaudeSignInStatusUseCase.getClaudeSignInStatus(NAS)).thenReturn(
            ClaudeSignInStatus.read(NAS, "nas", EffectiveUser.of("geir"), SIGNED_IN_JSON));

        mockMvc.perform(get("/machines/" + NAS.value() + "/claude-sign-in"));
        mockMvc.perform(get("/machines/" + NAS.value() + "/claude-sign-in"));

        verify(events, times(1)).publish("vpn-peers", "claude-standing-changed", "");
    }
}
