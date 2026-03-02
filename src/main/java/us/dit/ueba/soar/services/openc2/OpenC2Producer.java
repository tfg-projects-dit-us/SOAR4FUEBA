package us.dit.ueba.soar.services.openc2;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.oasis.openc2.lycan.OpenC2Message;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Productor simple de comandos OpenC2 Envía mensajes OpenC2 a través de HTTP a
 * un servidor
 */
public class OpenC2Producer {

    private String serverUrl;
    private ObjectMapper mapper;

    public OpenC2Producer(String serverUrl) {
        this.serverUrl = serverUrl;
        this.mapper = new ObjectMapper();
    }

    /**
     * Envía un comando OpenC2 al servidor
     */
    public String sendCommand(OpenC2Message message) throws Exception {
        URL url = new URL(serverUrl + "/command");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        // Serializar el mensaje a JSON
        String jsonMessage = mapper.writeValueAsString(message);
        System.out.println("📤 Enviando comando: " + jsonMessage);

        // Enviar el mensaje
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonMessage.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // Leer la respuesta
        int responseCode = conn.getResponseCode();
        String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        System.out.println("✅ Respuesta (código " + responseCode + "): " + response);
        return response;
    }
}
