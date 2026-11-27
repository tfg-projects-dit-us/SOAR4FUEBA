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
package us.dit.ueba.soar.services.ocsf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import us.dit.ueba.soar.ocsf.DetectionFinding;
import us.dit.ueba.soar.ocsf.IncidentFinding;

/**
 * Pruebas de {@link OcsfMessageValidator}. Como {@link OcsfSchemaRegistryTest}, monta el registro a mano (sin Spring)
 * y con un servidor inalcanzable, para que los esquemas salgan del classpath. En la aplicación no hay que hacer esto:
 * el validador se inyecta ya configurado.
 */
class OcsfMessageValidatorTest {

    private static final int DETECTION_FINDING = 2004;

    private final ObjectMapper mapper = new ObjectMapper();
    private OcsfMessageValidator validator;
    private String sample;

    @TempDir
    Path cacheDir;

    @BeforeEach
    void setUp() throws IOException {
        OcsfProperties properties = new OcsfProperties(List.of("detection_finding"),
                new OcsfProperties.Schema(URI.create("http://localhost:1"), Duration.ofSeconds(1), cacheDir));
        OcsfSchemaRegistry registry = new OcsfSchemaRegistry(properties, mapper);
        registry.load();
        validator = new OcsfMessageValidator(registry, mapper);
        try (InputStream in = getClass().getResourceAsStream("/ocsf/detection_finding-sample.json")) {
            sample = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aceptaUnEventoValido() {
        OcsfValidationResult result = validator.validate(sample);

        assertTrue(result.isValid(), result.errors()::toString);
        assertEquals(DETECTION_FINDING, result.classUid());
        assertEquals("detection_finding", result.className());
        assertTrue(validator.isValid(sample));
    }

    @Test
    void aceptaElEventoDentroDeUnCloudEvent() throws IOException {
        // data como objeto JSON y como texto JSON: los dos formatos que puede producir un emisor
        JsonNode event = mapper.readTree(sample);
        String conObjeto = cloudEvent(event);
        String conTexto = cloudEvent(mapper.getNodeFactory().textNode(sample));

        for (String mensaje : List.of(conObjeto, conTexto)) {
            OcsfValidationResult result = validator.validate(mensaje);
            assertTrue(result.isValid(), result.errors()::toString);
            assertEquals(event, result.event(), "debe devolver el evento OCSF, no el CloudEvent");
        }
    }

    @Test
    void informaDeTodosLosErroresDelEsquema() throws IOException {
        ObjectNode event = (ObjectNode) mapper.readTree(sample);
        event.remove("finding_info");            // obligatorio
        event.put("severity_id", 42);            // fuera del enum
        event.put("atributo_inventado", "x");    // additionalProperties=false

        OcsfValidationResult result = validator.validate(event.toString());

        assertFalse(result.isValid());
        assertEquals("detection_finding", result.className());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("finding_info")), result.errors()::toString);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("severity_id")), result.errors()::toString);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("atributo_inventado")), result.errors()::toString);
    }

    @Test
    void rechazaMensajesQueNoSonEventosOcsf() {
        assertError(validator.validate("hola"), "no es JSON");
        assertError(validator.validate(""), "vacío");
        assertError(validator.validate((String) null), "vacío");
        assertError(validator.validate("[1, 2]"), "objeto JSON");
        assertError(validator.validate("{\"mensaje\": \"sin class_uid\"}"), "class_uid");
        // Clase OCSF que no está en ocsf.classes (3002 = authentication)
        OcsfValidationResult result = validator.validate("{\"class_uid\": 3002}");
        assertError(result, "no registrada");
        assertEquals(3002, result.classUid());
        assertNull(result.className());
        // CloudEvent cuyo data es texto libre
        assertError(validator.validate(cloudEvent(mapper.getNodeFactory().textNode("hola"))), "data");
    }

    @Test
    void validaEventosYaDeserializados() throws IOException {
        DetectionFinding pojo = mapper.readValue(sample, DetectionFinding.class);
        Map<?, ?> mapa = mapper.readValue(sample, Map.class);

        assertTrue(validator.validate(pojo).isValid());
        assertTrue(validator.validate(mapa).isValid());
        assertTrue(validator.validate((Object) mapper.readTree(sample)).isValid());
    }

    @Test
    void requireValidDevuelveElEventoOLanzaExcepcion() throws IOException {
        assertEquals(mapper.readTree(sample), validator.requireValid(sample));

        DetectionFinding finding = validator.readValid(cloudEvent(mapper.readTree(sample)), DetectionFinding.class);
        assertEquals(DETECTION_FINDING, finding.getClassUid());

        OcsfValidationException e = assertThrows(OcsfValidationException.class,
                () -> validator.requireValid("{\"class_uid\": 2004}"));
        assertFalse(e.getResult().isValid());
        assertTrue(e.getMessage().contains("detection_finding"), e.getMessage());
    }

    @Test
    void indicaElTipoDeMensajeParaActuarSegunLaClase() {
        OcsfValidationResult result = validator.validate(sample);

        assertEquals(DetectionFinding.class, result.javaType());
        assertTrue(result.is(DetectionFinding.class));
        assertFalse(result.is(IncidentFinding.class));

        // Validar una vez y convertir después al tipo detectado
        Object pojo = validator.convert(result);
        String accion = switch (pojo) {
            case DetectionFinding d -> "detección " + d.getFindingInfo().getUid();
            case IncidentFinding i -> "incidente";
            default -> "otra";
        };
        assertTrue(accion.startsWith("detección "), accion);
        assertEquals(DetectionFinding.class, validator.readValid(sample).getClass());
        assertEquals(DetectionFinding.class, validator.convert(result, DetectionFinding.class).getClass());
    }

    @Test
    void noConvierteAOtraClaseNiEventosNoValidos() {
        OcsfValidationResult result = validator.validate(sample);
        assertThrows(IllegalArgumentException.class, () -> validator.convert(result, IncidentFinding.class));
        assertThrows(IllegalArgumentException.class, () -> validator.readValid(sample, IncidentFinding.class));

        OcsfValidationResult noValido = validator.validate("{\"class_uid\": 2004}");
        assertFalse(noValido.isValid());
        assertEquals(DetectionFinding.class, noValido.javaType(), "aunque no sea válido se conoce su clase");
        assertThrows(IllegalArgumentException.class, () -> validator.convert(noValido));
        assertThrows(OcsfValidationException.class, () -> validator.readValid("hola"));
    }

    @Test
    void sinPojoSeValidaPeroNoSeConvierte() {
        // Paquete sin POJOs: como una clase cuyo esquema se descarga al arrancar, sin recompilar
        OcsfProperties properties = new OcsfProperties(List.of("detection_finding"),
                new OcsfProperties.Schema(URI.create("http://localhost:1"), Duration.ofSeconds(1), cacheDir),
                "paquete.inexistente");
        OcsfSchemaRegistry registry = new OcsfSchemaRegistry(properties, mapper);
        registry.load();
        OcsfMessageValidator sinPojos = new OcsfMessageValidator(registry, mapper);

        OcsfValidationResult result = sinPojos.validate(sample);
        assertTrue(result.isValid(), result.errors()::toString);
        assertEquals("detection_finding", result.className());
        assertNull(result.javaType());
        assertThrows(IllegalStateException.class, () -> sinPojos.convert(result));
        // Con la clase indicada explícitamente sí se puede convertir
        assertEquals(DETECTION_FINDING, sinPojos.convert(result, DetectionFinding.class).getClassUid());
    }

    private static void assertError(OcsfValidationResult result, String texto) {
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains(texto)),
                "se esperaba un error con «" + texto + "»: " + result.errors());
    }

    private String cloudEvent(JsonNode data) {
        ObjectNode ce = mapper.createObjectNode()
                .put("specversion", "1.0")
                .put("id", "1")
                .put("source", "/tests/ocsf")
                .put("type", "detectionfinding")
                .put("datacontenttype", "application/json");
        ce.set("data", data);
        return ce.toString();
    }
}
