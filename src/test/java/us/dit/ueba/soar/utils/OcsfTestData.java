package us.dit.ueba.soar.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Mensajes OCSF de prueba para los tests que envían eventos por Kafka.
 */
public final class OcsfTestData {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SAMPLE = "/ocsf/detection_finding-sample.json";

    private OcsfTestData() {
    }

    /**
     * Evento OCSF Detection Finding válido (el de {@value #SAMPLE}) con el título indicado. Como el título es
     * distinto en cada test, el texto del evento sirve para localizar la instancia de proceso que crea.
     *
     * @return el evento en texto JSON, tal como queda en la variable {@code incidencia} del proceso
     */
    public static String detectionFinding(String titulo) {
        try (InputStream in = OcsfTestData.class.getResourceAsStream(SAMPLE)) {
            ObjectNode evento = (ObjectNode) MAPPER.readTree(in);
            ((ObjectNode) evento.path("finding_info")).put("title", titulo);
            return MAPPER.writeValueAsString(evento);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * CloudEvent (JSON estructurado) como el que espera Kogito. {@code type} es el nombre del mensaje BPMN y
     * {@code data} se envía como texto (escapado por Jackson), porque la variable del proceso es un String.
     */
    public static String cloudEvent(String tipo, String datos) {
        ObjectNode ce = MAPPER.createObjectNode()
                .put("specversion", "1.0")
                .put("id", UUID.randomUUID().toString())
                .put("source", "/tests/soar4fueba")
                .put("type", tipo)
                .put("datacontenttype", "application/json")
                .put("data", datos);
        return ce.toString();
    }
}
