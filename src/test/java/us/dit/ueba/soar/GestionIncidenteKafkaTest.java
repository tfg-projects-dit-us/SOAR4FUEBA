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
package us.dit.ueba.soar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.kie.kogito.Model;
import org.kie.kogito.process.Process;
import org.kie.kogito.process.ProcessInstance;
import org.kie.kogito.process.WorkItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import us.dit.ueba.soar.services.LogsService;

/**
 * Prueba de extremo a extremo del proceso gestion-incidente a través de Kafka.
 * <p>
 * Levanta un broker Kafka embebido, publica en el topic {@value #TOPIC} el CloudEvent que enviaría
 * un sistema externo y comprueba que:
 * <ol>
 * <li>se arranca una instancia del proceso con la incidencia recibida;</li>
 * <li>se ejecuta la tarea de servicio "Ajustar logs" ({@link LogsService#ajustarLogs(String)});</li>
 * <li>el proceso queda esperando en la tarea de usuario "Revisar-incidencia".</li>
 * </ol>
 */
@SpringBootTest(classes = KogitoApplication.class, properties = {
        // En src/test/resources/application.properties los consumidores Kafka están desactivados
        "spring.kafka.listener.auto-startup=true",
        // Leer desde el principio del topic aunque el mensaje se publique antes de que el consumidor esté listo
        "spring.kafka.consumer.auto-offset-reset=earliest" })
@EmbeddedKafka(partitions = 1, topics = GestionIncidenteKafkaTest.TOPIC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class GestionIncidenteKafkaTest {

    /** Topic asociado al mensaje "incidencia" en application.properties */
    static final String TOPIC = "soar-alertas";

    private static final Duration ESPERA = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    @Qualifier("gestion_incidente")
    Process<? extends Model> proceso;

    @MockitoSpyBean
    LogsService logsService;

    @Test
    void unMensajeKafkaArrancaElProcesoDeGestionDeIncidente() throws Exception {
        String incidencia = "Acceso sospechoso al servidor de nóminas " + UUID.randomUUID();

        kafka.send(TOPIC, cloudEvent("incidencia", incidencia)).get();

        // La tarea de servicio "Ajustar logs" recibe la incidencia del mensaje y el nivel
        // que fija su script de entrada (level = "uno")
        verify(logsService, timeout(ESPERA.toMillis())).ajustarLogs("uno", incidencia);

        // La instancia queda activa, esperando en la tarea de usuario "Revisar-incidencia"
        ProcessInstance<? extends Model> instancia = esperarInstancia(incidencia);
        assertEquals(ProcessInstance.STATE_ACTIVE, instancia.status());
        // Las tareas de usuario se identifican por su TaskName ("RevisarIncidencia"), no por el nombre del nodo
        List<String> tareas = instancia.workItems().stream().map(WorkItem::getName).toList();
        assertEquals(List.of("RevisarIncidencia"), tareas, "tareas de usuario pendientes");
    }

    /**
     * CloudEvent (formato JSON estructurado) como el que espera Kogito. El {@code type} es el nombre del
     * mensaje BPMN y {@code data} es el contenido que se asigna a la variable del proceso.
     */
    private static String cloudEvent(String tipo, String datos) {
        return """
                {
                  "specversion": "1.0",
                  "id": "%s",
                  "source": "/tests/gestion-incidente",
                  "type": "%s",
                  "datacontenttype": "application/json",
                  "data": "%s"
                }
                """.formatted(UUID.randomUUID(), tipo, datos);
    }

    private ProcessInstance<? extends Model> esperarInstancia(String incidencia) throws InterruptedException {
        Instant limite = Instant.now().plus(ESPERA);
        while (Instant.now().isBefore(limite)) {
            Optional<? extends ProcessInstance<? extends Model>> encontrada = proceso.instances().stream()
                    .filter(pi -> incidencia.equals(pi.variables().toMap().get("incidencia")))
                    .findFirst();
            if (encontrada.isPresent()) {
                return encontrada.get();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No se ha creado ninguna instancia del proceso para la incidencia: " + incidencia);
    }
}
