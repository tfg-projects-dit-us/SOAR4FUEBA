/*
 * Copyright 2026 Universidad de Sevilla/Departamento de Ingeniería Telemática
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file is part of SOAR4FUEBA - A SOAR solution based on BPM paradigm.
 */
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
