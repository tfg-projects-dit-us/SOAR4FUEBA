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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;

/**
 * Valida que un mensaje recibido (normalmente por Kafka) es un evento OCSF que cumple el esquema de su clase.
 * Crea el POJO generado para esa clase, si existe (p.ej. {@code DetectionFinding}).
 * <p>
 * Sirve para cualquier clase OCSF registrada en {@link OcsfSchemaRegistry} (las de {@code ocsf.classes}): la clase se
 * deduce del {@code class_uid} del propio evento. Admite el mensaje:
 * <ul>
 * <li>como texto JSON (el valor tal como llega de Kafka), {@link JsonNode}, {@code Map} o POJO OCSF generado;</li>
 * <li>suelto o dentro de un <a href="https://cloudevents.io">CloudEvent</a> en formato estructurado, que es como
 * llegan los mensajes a los procesos de Kogito. En ese caso se valida su {@code data}, aunque venga como texto
 * JSON.</li>
 * </ul>
 * Hay tres formas de usarlo, según lo que se quiera hacer con un mensaje no válido:
 * <ul>
 * <li>{@link #validate(String)}: devuelve el resultado con todos los errores, sin lanzar excepciones;</li>
 * <li>{@link #isValid(String)}: solo sí o no (cómodo en una tarea de servicio seguida de una puerta de enlace);</li>
 * <li>{@link #requireValid(String)} y {@link #readValid(String)}: devuelven el evento (o el POJO de su clase) y
 * lanzan {@link OcsfValidationException} si no es válido.</li>
 * </ul>
 * El resultado indica la clase OCSF recibida ({@link OcsfValidationResult#className()},
 * {@link OcsfValidationResult#javaType()}), para actuar según el tipo y convertirlo después con
 * {@link #convert(OcsfValidationResult)}.
 * Es un bean de Spring: se obtiene por inyección, con el registro de esquemas ya cargado al arrancar.
 */
@Service
public class OcsfMessageValidator {

    private static final Logger LOG = LoggerFactory.getLogger(OcsfMessageValidator.class);

    private final OcsfSchemaRegistry registry;
    private final ObjectMapper mapper;

    public OcsfMessageValidator(OcsfSchemaRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    /** Valida un mensaje en texto JSON, tal como llega de Kafka. */
    public OcsfValidationResult validate(String message) {
        if (message == null || message.isBlank()) {
            return invalid(null, null, "El mensaje está vacío");
        }
        try {
            return validate(mapper.readTree(message));
        } catch (JsonProcessingException e) {
            return invalid(null, null, "El mensaje no es JSON: " + e.getOriginalMessage());
        }
    }

    /** Valida un mensaje ya convertido a árbol JSON. */
    public OcsfValidationResult validate(JsonNode message) {
        if (message == null || message.isMissingNode() || message.isNull()) {
            return invalid(null, null, "El mensaje está vacío");
        }
        JsonNode event = message;
        if (isCloudEvent(message)) {
            event = message.get("data");
            if (event.isTextual()) {
                try {
                    event = mapper.readTree(event.asText());
                } catch (JsonProcessingException e) {
                    return invalid(null, null, "El campo data del CloudEvent no es JSON: " + e.getOriginalMessage());
                }
            }
        }
        if (!event.isObject()) {
            return invalid(event, null, "El evento OCSF debe ser un objeto JSON");
        }
        JsonNode uid = event.path("class_uid");
        if (!uid.canConvertToInt()) {
            return invalid(event, null, "El evento no tiene un class_uid OCSF");
        }
        int classUid = uid.asInt();
        if (!registry.isSupported(classUid)) {
            return invalid(event, classUid, "Clase OCSF " + classUid + " no registrada; registradas: "
                    + registry.loadedClasses() + " (se configuran en ocsf.classes)");
        }
        // Los mensajes empiezan por la ruta del atributo ($.finding_info...): ordenados quedan agrupados por atributo
        List<String> errors = registry.validate(event).stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();
        OcsfValidationResult result = new OcsfValidationResult(event, classUid,
                registry.loadedClasses().get(classUid), registry.javaType(classUid).orElse(null), errors);
        if (!result.isValid()) {
            LOG.debug("Evento OCSF {} no válido: {}", result.className(), errors);
        }
        return result;
    }

    /** Valida un evento ya deserializado (un {@code Map} o un POJO OCSF generado, p.ej. DetectionFinding). */
    public OcsfValidationResult validate(Object event) {
        if (event instanceof String text) {
            return validate(text);
        }
        if (event instanceof JsonNode node) {
            return validate(node);
        }
        return validate(event == null ? null : mapper.valueToTree(event));
    }

    /** Indica si el mensaje (texto JSON) es un evento OCSF válido. */
    public boolean isValid(String message) {
        return validate(message).isValid();
    }

    /**
     * Devuelve el evento OCSF del mensaje (extraído del CloudEvent si venía dentro de uno).
     *
     * @throws OcsfValidationException si no cumple el esquema
     */
    public JsonNode requireValid(String message) {
        OcsfValidationResult result = validate(message);
        if (!result.isValid()) {
            throw new OcsfValidationException(result);
        }
        return result.event();
    }

    /**
     * Valida el mensaje y lo convierte en el POJO de su clase OCSF, sea cual sea. Pensado para actuar según el tipo
     * con un {@code switch}:
     *
     * <pre>{@code
     * switch (validador.readValid(mensaje)) {
     *     case DetectionFinding d -> ...
     *     case IncidentFinding i -> ...
     *     default -> ...
     * }
     * }</pre>
     *
     * @throws OcsfValidationException si no cumple el esquema
     * @throws IllegalStateException   si su clase no tiene POJO (ver {@link OcsfValidationResult#javaType()})
     */
    public Object readValid(String message) {
        OcsfValidationResult result = validate(message);
        if (!result.isValid()) {
            throw new OcsfValidationException(result);
        }
        return convert(result);
    }

    /**
     * Valida el mensaje y lo convierte en el POJO OCSF indicado (p.ej. {@code DetectionFinding.class}).
     *
     * @throws OcsfValidationException  si no cumple el esquema
     * @throws IllegalArgumentException si el evento es de otra clase OCSF
     */
    public <T> T readValid(String message, Class<T> type) {
        OcsfValidationResult result = validate(message);
        if (!result.isValid()) {
            throw new OcsfValidationException(result);
        }
        return convert(result, type);
    }

    /**
     * Convierte un evento ya validado en el POJO de su clase ({@link OcsfValidationResult#javaType()}). Sirve para
     * validar una vez, decidir con el resultado y convertir después sin volver a validar.
     *
     * @throws IllegalArgumentException si el resultado no es válido
     * @throws IllegalStateException    si su clase no tiene POJO
     */
    public Object convert(OcsfValidationResult result) {
        requireValidResult(result);
        if (result.javaType() == null) {
            throw new IllegalStateException("La clase OCSF " + result.className() + " (" + result.classUid()
                    + ") no tiene POJO: hay que recompilar para generarlo");
        }
        return toPojo(result.event(), result.javaType());
    }

    /**
     * Convierte un evento ya validado en el POJO indicado.
     *
     * @throws IllegalArgumentException si el resultado no es válido o el evento es de otra clase OCSF
     */
    public <T> T convert(OcsfValidationResult result, Class<T> type) {
        requireValidResult(result);
        if (result.javaType() != null && !result.is(type)) {
            throw new IllegalArgumentException("El evento es de la clase OCSF " + result.className() + " ("
                    + result.javaType().getSimpleName() + "), no " + type.getSimpleName());
        }
        return toPojo(result.event(), type);
    }

    private static void requireValidResult(OcsfValidationResult result) {
        if (!result.isValid()) {
            throw new IllegalArgumentException("Solo se pueden convertir eventos válidos: " + result.errors());
        }
    }

    private <T> T toPojo(JsonNode event, Class<T> type) {
        try {
            return mapper.treeToValue(event, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "El evento OCSF no se puede convertir en " + type.getSimpleName() + ": " + e.getOriginalMessage(),
                    e);
        }
    }

    /** CloudEvent en formato estructurado: lleva specversion y el evento en data. */
    private static boolean isCloudEvent(JsonNode message) {
        return message.isObject() && message.has("specversion") && message.has("data");
    }

    private static OcsfValidationResult invalid(JsonNode event, Integer classUid, String error) {
        return new OcsfValidationResult(event, classUid, null, null, List.of(error));
    }
}
