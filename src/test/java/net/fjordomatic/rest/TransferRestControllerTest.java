package net.fjordomatic.rest;

import net.fjordomatic.application.GetTransfersUseCase;
import net.fjordomatic.application.StartTransferUseCase;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.TestMachineIds;
import net.fjordomatic.domain.Transfer;
import net.fjordomatic.domain.port.ForSubscribingToEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TransferRestControllerTest {

    private static MachineId mid(String name) {
        return TestMachineIds.of(name);
    }

    @Mock StartTransferUseCase startTransfer;
    @Mock GetTransfersUseCase getTransfers;
    @Mock ForSubscribingToEvents forSubscribingToEvents;

    @InjectMocks TransferRestController controller;

    private static Transfer running(String id) {
        return Transfer.starting(id, mid("apalveien5"), "apalveien5", "/home/geir/notes.txt", true,
            mid("colina27"), "colina27", "/backup");
    }

    @Test
    void post_startsATransfer_andReturnsItsJson() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(startTransfer.startTransfer(mid("apalveien5"), "apalveien5", "/home/geir/notes.txt", null, mid("colina27"), "colina27", "/backup"))
            .thenReturn(running("t1"));

        mvc.perform(post("/transfers").contentType("application/json").content("""
                {"sourceMachineId":"%s","sourceMachine":"apalveien5","sourcePath":"/home/geir/notes.txt","at":null,
                 "destMachineId":"%s","destMachine":"colina27","destPath":"/backup"}"""
                .formatted(mid("apalveien5"), mid("colina27"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("t1"))
            .andExpect(jsonPath("$.sourceMachine").value("apalveien5"))
            .andExpect(jsonPath("$.sourcePath").value("/home/geir/notes.txt"))
            .andExpect(jsonPath("$.destMachine").value("colina27"))
            .andExpect(jsonPath("$.destPath").value("/backup"))
            .andExpect(jsonPath("$.state").value("RUNNING"))
            .andExpect(jsonPath("$.bytesCopied").value(0))
            .andExpect(jsonPath("$.totalBytes").doesNotExist())
            .andExpect(jsonPath("$.error").doesNotExist());

        verify(startTransfer).startTransfer(mid("apalveien5"), "apalveien5", "/home/geir/notes.txt", null, mid("colina27"), "colina27", "/backup");
    }

    @Test
    void post_passesTheArchiveCoordinateThroughForARestore() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(startTransfer.startTransfer(any(), any(), any(), eq("ab12"), any(), any(), any())).thenReturn(running("t2"));

        mvc.perform(post("/transfers").contentType("application/json").content("""
                {"sourceMachineId":"%s","sourceMachine":"nas","sourcePath":"/a/x.txt","at":"ab12",
                 "destMachineId":"%s","destMachine":"nas","destPath":"/restore"}"""
                .formatted(mid("nas"), mid("nas"))))
            .andExpect(status().isOk());

        verify(startTransfer).startTransfer(mid("nas"), "nas", "/a/x.txt", "ab12", mid("nas"), "nas", "/restore");
    }

    @Test
    void post_aNoOpOrBadPath_isABadRequest() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(startTransfer.startTransfer(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("A transfer's source and destination are the same file"));

        mvc.perform(post("/transfers").contentType("application/json").content("""
                {"sourceMachineId":"%s","sourceMachine":"nas","sourcePath":"/a/b/c.txt","at":null,
                 "destMachineId":"%s","destMachine":"nas","destPath":"/a/b"}"""
                .formatted(mid("nas"), mid("nas"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void get_listsTransfers() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        Transfer done = running("t1").withTotal(2048L).progressed(2048L).completed();
        when(getTransfers.getTransfers()).thenReturn(List.of(done));

        mvc.perform(get("/transfers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("t1"))
            .andExpect(jsonPath("$[0].state").value("DONE"))
            .andExpect(jsonPath("$[0].bytesCopied").value(2048))
            .andExpect(jsonPath("$[0].totalBytes").value(2048));
    }

    @Test
    void events_subscribesToTheTransfersTopic() {
        controller.transferEvents();
        verify(forSubscribingToEvents).subscribe("transfers");
    }
}
