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
package us.dit.ueba.soar.gui;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import us.dit.ueba.soar.KogitoApplication;

/**
 * Prueba de la consola SOC ({@code /soc/}) y su API ({@link TareasSocController}): un analista ve la tarea
 * "Revisar-incidencia" que crea una incidencia recibida por Kafka, la consulta, la reclama, guarda un borrador, la
 * completa y la encuentra después entre las terminadas.
 */
@SpringBootTest(classes = KogitoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest" })
@EmbeddedKafka(partitions = 1, topics = ConsolaSocTest.TOPIC, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class ConsolaSocTest {

    static final String TOPIC = "soar-alertas";

    private static final Duration ESPERA = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @LocalServerPort
    int puerto;

    @BeforeEach
    void configurar() {
        RestAssured.port = puerto;
    }

    @Test
    void laPaginaEsPublicaPeroLaApiExigeCredenciales() {
        given().get("/soc/index.html").then().statusCode(200).body(containsString("Consola SOC"));
        given().get("/soc/app.js").then().statusCode(200);
        given().redirects().follow(false).get("/soc").then().statusCode(302);

        // Con X-Requested-With, Spring Security responde 401 sin pedir credenciales al navegador
        given().header("X-Requested-With", "XMLHttpRequest").get("/soc/api/tareas")
                .then().statusCode(401).header("WWW-Authenticate", nullValue());
        given().auth().preemptive().basic("analista", "mala").get("/soc/api/yo").then().statusCode(401);

        analista().get("/soc/api/yo").then().statusCode(200)
                .body("usuario", is("analista"))
                .body("grupos", contains("soc"));
    }

    @Test
    void elAnalistaConsultaReclamaEjecutaYCierraLaTarea() throws Exception {
        String incidencia = "Movimiento lateral desde el equipo de contabilidad " + UUID.randomUUID();
        kafka.send(TOPIC, cloudEvent(incidencia)).get();
        String id = esperarTareaPendiente(incidencia);

        // Consultar: datos de la tarea, incidencia, contexto del proceso y formulario de salida
        analista().get("/soc/api/tareas/{id}", id).then().statusCode(200)
                .body("tarea.nombre", is("RevisarIncidencia"))
                // DefaultUserTaskLifeCycle.ACTIVE se llama "Ready"
                .body("tarea.estado", is("Ready"))
                .body("tarea.terminada", is(false))
                .body("tarea.propietario", nullValue())
                .body("tarea.grupos", contains("soc"))
                .body("tarea.entradas.incidencia", is(incidencia))
                .body("tarea.entradas", not(org.hamcrest.Matchers.hasKey("GroupId")))
                .body("procesoActivo", is(true))
                .body("datosProceso.incidencia", is(incidencia))
                .body("formulario.nombre", contains("resuelta"))
                .body("formulario.tipo", contains("boolean"))
                .body("transiciones", hasItem("claim"));

        // Reclamar
        analista().contentType(ContentType.JSON).body(Map.of())
                .post("/soc/api/tareas/{id}/claim", id).then().statusCode(200)
                .body("tarea.estado", is("Reserved"))
                .body("tarea.propietario", is("analista"))
                .body("transiciones", hasItems("complete", "release"));

        // Ejecutar: guardar un borrador del resultado sin cerrar la tarea
        analista().contentType(ContentType.JSON).body(Map.of("resuelta", false))
                .put("/soc/api/tareas/{id}/salidas", id).then().statusCode(200)
                .body("tarea.estado", is("Reserved"))
                .body("tarea.salidas.resuelta", is(false));

        // Una transición no soportada se rechaza
        analista().contentType(ContentType.JSON).body(Map.of())
                .post("/soc/api/tareas/{id}/borrar", id).then().statusCode(400);

        // Cerrar: completar con la incidencia resuelta. El proceso termina
        analista().contentType(ContentType.JSON).body(Map.of("resuelta", true))
                .post("/soc/api/tareas/{id}/complete", id).then().statusCode(200)
                .body("tarea.terminada", is(true))
                .body("tarea.estado", is("Completed"))
                .body("tarea.salidas.resuelta", is(true))
                .body("tarea.entradas.incidencia", is(incidencia))
                .body("procesoActivo", is(false))
                .body("transiciones", org.hamcrest.Matchers.empty());

        // Ya no está entre las pendientes, sí entre las terminadas
        analista().get("/soc/api/tareas?estado=pendientes").then().statusCode(200)
                .body("id", not(hasItem(id)));
        analista().get("/soc/api/tareas?estado=terminadas").then().statusCode(200)
                .body("id", hasItem(id))
                .body("find { it.id == '%s' }.cerradaPor".formatted(id), is("analista"));
        analista().get("/soc/api/tareas/{id}", id).then().statusCode(200)
                .body("tarea.terminada", is(true));

        analista().get("/soc/api/tareas?estado=otra").then().statusCode(400);
        analista().get("/soc/api/tareas/{id}", "no-existe").then().statusCode(404);
    }

    private static RequestSpecification analista() {
        return given().auth().preemptive().basic("analista", "analista");
    }

    /** Espera a que el analista vea una tarea pendiente para la incidencia y devuelve su id. */
    private String esperarTareaPendiente(String incidencia) throws InterruptedException {
        Instant limite = Instant.now().plus(ESPERA);
        while (Instant.now().isBefore(limite)) {
            List<String> ids = analista().get("/soc/api/tareas").then().statusCode(200).extract()
                    .jsonPath().getList("findAll { it.entradas.incidencia == '%s' }.id".formatted(incidencia));
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No aparece ninguna tarea pendiente para la incidencia: " + incidencia);
    }

    private static String cloudEvent(String datos) {
        return """
                {
                  "specversion": "1.0",
                  "id": "%s",
                  "source": "/tests/consola-soc",
                  "type": "detectionfinding",
                  "datacontenttype": "application/json",
                  "data": "%s"
                }
                """.formatted(UUID.randomUUID(), datos);
    }
}
