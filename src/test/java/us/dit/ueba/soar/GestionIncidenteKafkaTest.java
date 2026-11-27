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


import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.kie.kogito.Model;
import org.kie.kogito.auth.IdentityProvider;
import org.kie.kogito.auth.IdentityProviders;
import org.kie.kogito.process.Process;
import org.kie.kogito.process.ProcessInstance;
import org.kie.kogito.process.WorkItem;
import org.kie.kogito.usertask.UserTaskService;
import org.kie.kogito.usertask.impl.lifecycle.DefaultUserTaskLifeCycle;
import org.kie.kogito.usertask.view.UserTaskView;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.restassured.RestAssured;
import static io.restassured.RestAssured.given;
import io.restassured.http.ContentType;
import us.dit.ueba.soar.services.LogsService;
import us.dit.ueba.soar.utils.KieTestUtils;

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
@SpringBootTest(classes = KogitoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // En src/test/resources/application.properties los consumidores Kafka están desactivados
        "spring.kafka.listener.auto-startup=true",
        // Leer desde el principio del topic aunque el mensaje se publique antes de que el consumidor esté listo
        "spring.kafka.consumer.auto-offset-reset=earliest" })
@EmbeddedKafka(partitions = 1, topics = GestionIncidenteKafkaTest.TOPIC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class GestionIncidenteKafkaTest {

    /** Topic asociado al mensaje "detectionfinding" en application.properties */
    static final String TOPIC = "soar-alertas";

    private static final Duration ESPERA = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired 
    KieTestUtils testUtils;

    @Autowired
    @Qualifier("gestion_incidente")
    Process<? extends Model> proceso;

    @MockitoSpyBean
    LogsService logsService;

    /** API de tareas humanas de Kogito (la que hay detrás de /usertasks/instance) */
    @Autowired
    UserTaskService userTasks;

    /**
     * Usuario que actúa sobre las tareas. Debe pertenecer al grupo asignado a la tarea en el BPMN (GroupId = soc):
     * Kogito solo deja ver y reclamar una tarea a sus usuarios/grupos potenciales, su propietario o sus administradores
     */
    private static final IdentityProvider ANALISTA = IdentityProviders.of("analista", "soc");

    @LocalServerPort
    int puerto;

    @Test
    void unMensajeKafkaArrancaElProcesoDeGestionDeIncidente() throws Exception {
        String incidencia = "Acceso sospechoso al servidor de nóminas " + UUID.randomUUID();

        kafka.send(TOPIC, cloudEvent("detectionfinding", incidencia)).get();

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
     * Recorre el bucle de la tarea humana "Revisar-incidencia" con la API de tareas de usuario de Kogito
     * ({@link UserTaskService}, la misma que usa el endpoint REST {@code /usertasks/instance}):
     * <ol>
     * <li>se crea la tarea y se comprueban sus datos;</li>
     * <li>un analista la reclama ({@code claim}) y la completa con {@code resuelta=false}: el proceso
     * vuelve a la puerta de unión y se crea una tarea nueva;</li>
     * <li>se completa la segunda tarea con {@code resuelta=true}: se ejecuta "Reajustar Logs" y el proceso
     * termina.</li>
     * </ol>
     */
    @Test
    void laRevisionDeLaIncidenciaSeRepiteHastaQueSeResuelve() throws Exception {
        String incidencia = "Escalada de privilegios en el controlador de dominio " + UUID.randomUUID();
        kafka.send(TOPIC, cloudEvent("detectionfinding", incidencia)).get();
        String idInstancia = esperarInstancia(incidencia).id();

        // 1. Se crea la tarea humana, activa y sin propietario todavía
        UserTaskView tarea = esperarTareaActiva(idInstancia);
        if (tarea!=null) {
            testUtils.printTaskInfo(tarea);
        }
        assertEquals("RevisarIncidencia", tarea.getTaskName());
        assertEquals(DefaultUserTaskLifeCycle.ACTIVE, tarea.getStatus());
        assertNull(tarea.getActualOwner());
        assertEquals("gestion_incidente", tarea.getProcessInfo().getProcessId());
        assertEquals(Set.of("soc"), tarea.getPotentialGroups());
        // Un usuario ajeno al grupo "soc" no ve la tarea
        String idTarea = tarea.getId();
        assertTrue(userTasks.list(IdentityProviders.of("intruso", "rrhh")).stream()
                .noneMatch(t -> t.getId().equals(idTarea)));

        // 2. El analista la reclama: pasa a "Reserved" y él es el propietario
        tarea = userTasks.transition(tarea.getId(), DefaultUserTaskLifeCycle.CLAIM, Map.of(), ANALISTA).orElseThrow();
        assertEquals(DefaultUserTaskLifeCycle.RESERVED, tarea.getStatus());
        assertEquals("analista", tarea.getActualOwner());

        // ... y la completa indicando que la incidencia NO está resuelta
        String primeraTarea = tarea.getId();
        tarea = userTasks.transition(primeraTarea, DefaultUserTaskLifeCycle.COMPLETE, Map.of("resuelta", false), ANALISTA)
                .orElseThrow();
        assertEquals(DefaultUserTaskLifeCycle.COMPLETED, tarea.getStatus());
        assertEquals(Boolean.FALSE, tarea.getOutputs().get("resuelta"));

        // El proceso sigue activo y ha creado otra tarea de revisión (resuelta == false)
        UserTaskView segunda = esperarTareaActiva(idInstancia);
        assertNotEquals(primeraTarea, segunda.getId(), "debe ser una tarea nueva");
        testUtils.printTaskInfo(segunda);
        assertEquals(Boolean.FALSE, proceso.instances().findById(idInstancia).orElseThrow()
                .variables().toMap().get("resuelta"));

        // 3. Se completa la segunda tarea con la incidencia resuelta: se reajustan los logs y termina
        userTasks.transition(segunda.getId(), DefaultUserTaskLifeCycle.CLAIM, Map.of(), ANALISTA);
        userTasks.transition(segunda.getId(), DefaultUserTaskLifeCycle.COMPLETE, Map.of("resuelta", true), ANALISTA);

        // "Ajustar logs" al principio y "Reajustar Logs" al final, ambas con level = "uno"
        verify(logsService, timeout(ESPERA.toMillis()).times(2)).ajustarLogs("uno", incidencia);
        // Las instancias terminadas desaparecen del almacén en memoria
        assertTrue(proceso.instances().findById(idInstancia).isEmpty(), "el proceso debería haber terminado");
        assertTrue(tareasActivas(idInstancia).isEmpty(), "no deben quedar tareas pendientes");
    }

    /**
     * La misma tarea humana a través de la API REST ({@code /usertasks/instance}), con la autenticación de
     * {@code SeguridadConfig}: la identidad sale de las credenciales HTTP Basic, no de los parámetros de la URL.
     */
    @Test
    void laTareaHumanaSeGestionaPorRestConElUsuarioAutenticado() throws Exception {
        RestAssured.port = puerto;
        String incidencia = "Exfiltración de datos por DNS " + UUID.randomUUID();
        kafka.send(TOPIC, cloudEvent("detectionfinding", incidencia)).get();
        String idInstancia = esperarInstancia(incidencia).id();
        String idTarea = esperarTareaActiva(idInstancia).getId();

        // Sin credenciales, o con credenciales incorrectas, no hay acceso
        given().get("/usertasks/instance").then().statusCode(401);
        given().auth().preemptive().basic("analista", "mala").get("/usertasks/instance").then().statusCode(401);

        // El analista (grupo soc) ve la tarea
        given().auth().preemptive().basic("analista", "analista")
                .get("/usertasks/instance/{id}", idTarea)
                .then().statusCode(200)
                .body("taskName", is("RevisarIncidencia"))
                .body("processInfo.processInstanceId", is(idInstancia));

        // Intenta reclamarla en nombre de otro (?user=jefe): no es admin, así que se ignora y queda a su nombre
        given().auth().preemptive().basic("analista", "analista")
                .contentType(ContentType.JSON)
                .queryParam("user", "jefe").queryParam("group", "soc")
                .body(Map.of("transitionId", "claim", "data", Map.of()))
                .post("/usertasks/instance/{id}/transition", idTarea)
                .then().statusCode(200)
                .body("status.name", is("Reserved"))
                .body("actualOwner", is("analista"));

        // La completa con la incidencia resuelta: se reajustan los logs y el proceso termina
        given().auth().preemptive().basic("analista", "analista")
                .contentType(ContentType.JSON)
                .body(Map.of("transitionId", "complete", "data", Map.of("resuelta", true)))
                .post("/usertasks/instance/{id}/transition", idTarea)
                .then().statusCode(200)
                .body("status.name", is("Completed"))
                .body("outputs.resuelta", is(true));

        verify(logsService, timeout(ESPERA.toMillis()).times(2)).ajustarLogs("uno", incidencia);
        assertTrue(proceso.instances().findById(idInstancia).isEmpty(), "el proceso debería haber terminado");
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

    /** Tareas de usuario activas (no terminadas) de una instancia de proceso, vistas por {@link #ANALISTA}. */
    private List<UserTaskView> tareasActivas(String idInstancia) {
        return userTasks.list(ANALISTA).stream()
                // Una tarea recién creada puede listarse un instante antes de tener asignado su processInfo
                .filter(t -> t.getProcessInfo() != null)
                .filter(t -> idInstancia.equals(t.getProcessInfo().getProcessInstanceId()))
                .filter(t -> !t.getStatus().isTerminate())
                .toList();
    }

    /** Espera a que la instancia tenga exactamente una tarea de usuario activa y la devuelve. */
    private UserTaskView esperarTareaActiva(String idInstancia) throws InterruptedException {
        Instant limite = Instant.now().plus(ESPERA);
        while (Instant.now().isBefore(limite)) {
            List<UserTaskView> activas = tareasActivas(idInstancia);
            if (activas.size() >= 1) {
                return activas.get(0);
            }
            Thread.sleep(200);
        }
        throw new AssertionError("La instancia " + idInstancia + " no tiene una tarea de usuario activa");
    }
}
