package com.vokyo.backend.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Points the backend at a server that accepts connections and never answers. That is
 * the worst way Redis can fail: a refused connection fails at once, but a Redis that
 * hangs holds every caller for as long as the client is willing to wait.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "spring.ai.openai.api-key=dummy")
class RedisUnavailableIntegrationTests {

    private static final SilentServer REDIS = SilentServer.start();

    @DynamicPropertySource
    static void pointTheBackendAtTheSilentServer(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> "redis://127.0.0.1:" + REDIS.port());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @AfterAll
    static void stopTheSilentServer() {
        REDIS.stop();
    }

    @Test
    void theBackendStaysHealthy() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void aCommandFailsWithinASecondInsteadOfWaitingAMinute() {
        Duration elapsed = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            long started = System.nanoTime();
            assertThatThrownBy(() -> redis.opsForValue().get("anything"))
                    .isInstanceOf(DataAccessException.class);
            return Duration.ofNanos(System.nanoTime() - started);
        });

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    /** Accepts connections and never writes a byte back, like a Redis that has hung. */
    private static final class SilentServer {

        private final ServerSocket socket;
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();

        private SilentServer(ServerSocket socket) {
            this.socket = socket;
        }

        static SilentServer start() {
            try {
                SilentServer server = new SilentServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
                Thread acceptor = new Thread(server::acceptUntilClosed, "silent-redis");
                acceptor.setDaemon(true);
                acceptor.start();
                return server;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private void acceptUntilClosed() {
            while (!socket.isClosed()) {
                try {
                    accepted.add(socket.accept());
                } catch (IOException closed) {
                    return;
                }
            }
        }

        int port() {
            return socket.getLocalPort();
        }

        void stop() {
            try {
                socket.close();
                for (Socket connection : accepted) {
                    connection.close();
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

}
