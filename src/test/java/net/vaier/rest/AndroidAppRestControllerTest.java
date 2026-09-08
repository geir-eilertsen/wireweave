package net.vaier.rest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import net.vaier.application.GetAndroidAppUseCase;
import net.vaier.domain.AndroidApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

/**
 * The one public door the <b>Vaier app</b> is handed out through (#359). It carries no secret — the same
 * signed package for every visitor — which is why it may sit on the anonymous Traefik router: a phone has
 * to fetch the app <em>before</em> it can sign in, so requiring a session here would be a locked door with
 * the key behind it.
 */
@ExtendWith(MockitoExtension.class)
class AndroidAppRestControllerTest {

    @Mock GetAndroidAppUseCase getAndroidAppUseCase;

    @InjectMocks AndroidAppRestController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static AndroidApp app(byte[] payload) {
        return AndroidApp.of(payload.length, out -> {
            try {
                out.write(payload);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).orElseThrow();
    }

    @Test
    void servesThePackageAsAnInstallableAndroidDownload() throws Exception {
        byte[] payload = {'P', 'K', 3, 4, 9};
        when(getAndroidAppUseCase.androidApp()).thenReturn(Optional.of(app(payload)));

        // Invoked directly so the streamed body is asserted without the async dispatch a
        // StreamingResponseBody otherwise needs.
        ResponseEntity<StreamingResponseBody> response = controller.download();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType())
            .isEqualTo(MediaType.valueOf("application/vnd.android.package-archive"));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("attachment; filename=\"vaier.apk\"");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(payload.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo(payload);
    }

    @Test
    void answersNotFoundWhenTheImageCarriesNoApp() throws Exception {
        when(getAndroidAppUseCase.androidApp()).thenReturn(Optional.empty());

        mockMvc.perform(get("/app/android/vaier.apk")).andExpect(status().isNotFound());
    }

    @Test
    void answersAHeadRequestWithTheSameHeaders() throws Exception {
        // The launchpad asks HEAD before it paints the Android button — Vaier never offers what it cannot
        // serve — so a HEAD that 405s would hide a perfectly good app on every visit.
        when(getAndroidAppUseCase.androidApp()).thenReturn(Optional.of(app(new byte[]{1, 2, 3})));

        MvcResult started = mockMvc.perform(head("/app/android/vaier.apk"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/vnd.android.package-archive"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vaier.apk\""))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 3));
    }

    @Test
    void answersAHeadRequestWithNotFoundWhenThereIsNoApp() throws Exception {
        when(getAndroidAppUseCase.androidApp()).thenReturn(Optional.empty());

        mockMvc.perform(head("/app/android/vaier.apk")).andExpect(status().isNotFound());
    }
}
