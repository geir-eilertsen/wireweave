package net.fjordomatic.rest;

import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.TestMachineIds;
import net.fjordomatic.domain.port.ForPublishingEvents;
import net.fjordomatic.domain.port.ForSubscribingToEvents;
import net.fjordomatic.application.DeletePublishedServiceUseCase;
import net.fjordomatic.application.GetPublishableServicesUseCase;
import net.fjordomatic.application.GetPublishedServicesUseCase;
import net.fjordomatic.application.IgnorePublishableServiceUseCase;
import net.fjordomatic.application.PublishLanServiceUseCase;
import net.fjordomatic.application.PublishPeerServiceUseCase;
import net.fjordomatic.application.UnignorePublishableServiceUseCase;
import net.fjordomatic.application.UpdatePublishedServiceUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublishedServiceRestControllerTest {

    @Mock GetPublishedServicesUseCase getPublishedServicesUseCase;
    @Mock PublishPeerServiceUseCase publishPeerServiceUseCase;
    @Mock PublishLanServiceUseCase publishLanServiceUseCase;
    @Mock GetPublishableServicesUseCase getPublishableServicesUseCase;
    @Mock DeletePublishedServiceUseCase deletePublishedServiceUseCase;
    @Mock UpdatePublishedServiceUseCase updatePublishedServiceUseCase;
    @Mock IgnorePublishableServiceUseCase ignorePublishableServiceUseCase;
    @Mock UnignorePublishableServiceUseCase unignorePublishableServiceUseCase;
    @Mock ForPublishingEvents forPublishingEvents;
    @Mock ForSubscribingToEvents forSubscribingToEvents;

    @InjectMocks
    PublishedServiceRestController controller;

    @Test
    void subscribeToEvents_subscribesToPublishedServicesTopicViaPort() {
        SseEmitter emitter = new SseEmitter();
        when(forSubscribingToEvents.subscribe("published-services")).thenReturn(emitter);

        SseEmitter result = controller.subscribeToEvents();

        assertThat(result).isSameAs(emitter);
        verify(forSubscribingToEvents).subscribe("published-services");
    }

    @Test
    void publishLanService_forwardsTheMachineIdentityToUseCase() {
        MachineId printer = TestMachineIds.of("printer");
        var request = new PublishedServiceRestController.PublishLanRequest(
            "printer-ui", printer.value(), 9100, "http", false, false, null, null);

        ResponseEntity<?> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publishLanServiceUseCase).publishLanService(
            "printer-ui", printer, 9100, "http", false, false, null, null);
    }

    @Test
    void publishLanService_forwardsRootRedirectPathToUseCase() {
        MachineId rig = TestMachineIds.of("rig");
        var request = new PublishedServiceRestController.PublishLanRequest(
            "app", rig.value(), 3000, "http", false, false, "/builder/ui/", null);

        ResponseEntity<?> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publishLanServiceUseCase).publishLanService(
            "app", rig, 3000, "http", false, false, "/builder/ui/", null);
    }

    @Test
    void publishLanService_useCaseThrowsIllegalArgument_propagatesToGlobalHandler() {
        MachineId ghost = TestMachineIds.of("ghost");
        doThrow(new IllegalArgumentException("Unknown machine: " + ghost.value()))
            .when(publishLanServiceUseCase).publishLanService(
                "x", ghost, 80, "http", false, false, null, null);
        var request = new PublishedServiceRestController.PublishLanRequest(
            "x", ghost.value(), 80, "http", false, false, null, null);

        // The controller no longer hand-rolls a 400 body; the validation exception
        // propagates to GlobalExceptionHandler, which renders the uniform ApiError 400.
        assertThatThrownBy(() -> controller.publishLanService(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unknown machine: " + ghost.value());
    }

    @Test
    void publishService_useCaseThrowsIllegalArgument_propagatesToGlobalHandler() {
        doThrow(new IllegalArgumentException("A route already exists on app.example.com"))
            .when(publishPeerServiceUseCase).publishService(
                "10.13.13.2", 8080, "app", false, null, false, null);
        var request = new PublishedServiceRestController.PublishRequest(
            "10.13.13.2", 8080, "app", false, null, false, null);

        assertThatThrownBy(() -> controller.publishService(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A route already exists on app.example.com");
    }
}
