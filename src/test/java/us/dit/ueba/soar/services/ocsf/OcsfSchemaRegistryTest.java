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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.ValidationMessage;

import us.dit.ueba.soar.ocsf.DetectionFinding;

/**
 * Comprueba los esquemas OCSF que deja el build en el classpath y los POJOs generados a partir de ellos.
 * El evento de ejemplo procede de https://schema.ocsf.io/sample/classes/detection_finding (datos aleatorios).
 */
class OcsfSchemaRegistryTest {

    private static final int DETECTION_FINDING = 2004;

    private final ObjectMapper mapper = new ObjectMapper();
    private OcsfSchemaRegistry registry;

    @TempDir
    Path cacheDir;

    @BeforeEach
    void setUp() {
        // Servidor inalcanzable: todo debe salir del classpath
        OcsfProperties properties = new OcsfProperties(List.of("detection_finding"),
                new OcsfProperties.Schema(URI.create("http://localhost:1"), Duration.ofSeconds(1), cacheDir));
        registry = new OcsfSchemaRegistry(properties, mapper);
        registry.load();
    }

    @Test
    void cargaElEsquemaDesdeElClasspath() {
        assertTrue(registry.isSupported(DETECTION_FINDING));
        assertEquals("detection_finding", registry.loadedClasses().get(DETECTION_FINDING));
        assertTrue(registry.downloads().isDone(), "no debería haber descargas pendientes");
    }

    @Test
    void detectaEventosQueNoCumplenElEsquema() throws IOException {
        ObjectNode event = (ObjectNode) sample();
        event.remove("finding_info");            // obligatorio
        event.put("severity_id", 42);            // fuera del enum
        event.put("atributo_inventado", "x");    // additionalProperties=false

        Set<ValidationMessage> errors = registry.validate(event);

        assertTrue(errors.stream().anyMatch(m -> m.getMessage().contains("finding_info")), errors::toString);
        assertTrue(errors.stream().anyMatch(m -> m.getMessage().contains("severity_id")), errors::toString);
        assertTrue(errors.stream().anyMatch(m -> m.getMessage().contains("atributo_inventado")), errors::toString);
    }

    @Test
    void rechazaClasesNoRegistradas() {
        ObjectNode event = mapper.createObjectNode().put("class_uid", 3002); // authentication
        assertThrows(IllegalArgumentException.class, () -> registry.validate(event));
        assertThrows(IllegalArgumentException.class, () -> registry.validate(mapper.createObjectNode()));
    }

    @Test
    void elPojoGeneradoConservaTodoElEvento() throws IOException {
        JsonNode original = sample();

        DetectionFinding finding = mapper.treeToValue(original, DetectionFinding.class);

        assertEquals(DETECTION_FINDING, finding.getClassUid());
        assertNotNull(finding.getFindingInfo().getUid());
        assertNotNull(finding.getMetadata().getProduct());
        // Ida y vuelta sin perder datos (los números se comparan por valor: 5 y 5.0 son iguales)
        JsonNode roundTrip = mapper.valueToTree(finding);
        List<String> differences = new ArrayList<>();
        diff("", original, roundTrip, differences);
        assertTrue(differences.isEmpty(), "el POJO pierde o altera datos del evento: " + differences);
        assertFalse(roundTrip.isEmpty());
    }

    /** Acumula las rutas JSON en las que difieren dos árboles (los números se comparan por valor). */
    private static void diff(String path, JsonNode a, JsonNode b, List<String> out) {
        if (a.isNumber() && b.isNumber()) {
            if (a.decimalValue().compareTo(b.decimalValue()) != 0) {
                out.add(path + ": " + a + " -> " + b);
            }
        } else if (a.isObject() && b.isObject()) {
            Set<String> names = new TreeSet<>();
            a.fieldNames().forEachRemaining(names::add);
            b.fieldNames().forEachRemaining(names::add);
            names.forEach(n -> diff(path + "/" + n, a.path(n), b.path(n), out));
        } else if (a.isArray() && b.isArray() && a.size() == b.size()) {
            for (int i = 0; i < a.size(); i++) {
                diff(path + "/" + i, a.get(i), b.get(i), out);
            }
        } else if (!a.equals(b)) {
            out.add(path + ": " + a + " -> " + b);
        }
    }

    private JsonNode sample() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/ocsf/detection_finding-sample.json")) {
            return mapper.readTree(in);
        }
    }
}
