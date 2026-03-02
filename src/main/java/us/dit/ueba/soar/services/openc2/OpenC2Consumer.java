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
 * This file is part of SOAR4UEBA - A SOAR solution based on BPM paradigm.
 */
package us.dit.ueba.soar.services.openc2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.oasis.openc2.lycan.OpenC2Message;
import org.oasis.openc2.lycan.OpenC2Response;
import org.oasis.openc2.lycan.types.StatusCodeType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Consumidor simple de comandos OpenC2 Servidor HTTP que recibe y procesa
 * mensajes OpenC2
 */
public class OpenC2Consumer {

    private HttpServer server;
    private ObjectMapper mapper;
    private OpenC2CommandHandler commandHandler;

    public OpenC2Consumer(int port) throws IOException {
        this.mapper = new ObjectMapper();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.commandHandler = new OpenC2CommandHandler();

        // Registrar el handler para recibir comandos
        server.createContext("/command", new CommandHandler());
        server.setExecutor(null); // Usar executor por defecto
    }

    /**
     * Inicia el servidor
     */
    public void start() {
        server.start();
        System.out.println("🚀 Servidor OpenC2 iniciado en puerto " + server.getAddress().getPort());
    }

    /**
     * Detiene el servidor
     */
    public void stop() {
        server.stop(0);
        System.out.println("⛔ Servidor OpenC2 detenido");
    }

    /**
     * Handler HTTP para procesar comandos OpenC2
     */
    private class CommandHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Método no permitido");
                return;
            }

            try {
                // Leer el comando OpenC2
                String requestBody = readRequestBody(exchange);
                System.out.println("📥 Comando recibido: " + requestBody);

                // Deserializar a OpenC2Message
                OpenC2Message message = mapper.readValue(requestBody, OpenC2Message.class);

                // Procesar el comando
                OpenC2Response response = commandHandler.processCommand(message);

                // Serializar la respuesta
                String responseJson = mapper.writeValueAsString(response);
                System.out.println("📤 Respuesta: " + responseJson);

                // Enviar respuesta
                sendSuccess(exchange, responseJson);

            } catch (Exception e) {
                System.err.println("❌ Error procesando comando: " + e.getMessage());
                sendError(exchange, 400, "Error: " + e.getMessage());
            }
        }

        private String readRequestBody(HttpExchange exchange) throws IOException {
            InputStream is = exchange.getRequestBody();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        private void sendSuccess(HttpExchange exchange, String response) throws IOException {
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }

        private void sendError(HttpExchange exchange, int code, String message) throws IOException {
            byte[] responseBytes = message.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    /**
     * Handler que procesa los comandos OpenC2 Aquí es donde iría la lógica de
     * seguridad
     */
    public static class OpenC2CommandHandler {

        public OpenC2Response processCommand(OpenC2Message message) {
            // Procesar el comando OpenC2
            OpenC2Response response = new OpenC2Response();
            response.setStatusCode(StatusCodeType.OK);
            return response;
        }
    }
}
