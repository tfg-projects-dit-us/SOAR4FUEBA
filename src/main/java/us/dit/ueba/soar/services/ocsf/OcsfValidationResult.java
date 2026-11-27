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

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Resultado de validar un mensaje contra el esquema OCSF de su clase.
 * <p>
 * Además de si es válido, indica el tipo de mensaje recibido, para decidir qué hacer con él:
 * {@link #classUid()} y {@link #className()} identifican la clase OCSF, y {@link #javaType()} es el POJO al que se
 * puede convertir con {@link OcsfMessageValidator#convert(OcsfValidationResult)}.
 *
 * @param event     el evento OCSF validado (ya extraído del CloudEvent, si venía dentro de uno), o {@code null} si el
 *                  mensaje no era JSON
 * @param classUid  class_uid del evento (p.ej. 2004), o {@code null} si no lo tiene
 * @param className nombre de la clase OCSF (p.ej. {@code detection_finding}), o {@code null} si no está registrada
 * @param javaType  POJO generado para la clase (p.ej. {@code DetectionFinding.class}), o {@code null} si la clase no
 *                  está registrada o no tiene POJO (su esquema se obtuvo al arrancar, sin recompilar)
 * @param errors    errores encontrados; vacío si el evento es válido
 */
public record OcsfValidationResult(JsonNode event, Integer classUid, String className, Class<?> javaType,
        List<String> errors) {

    public OcsfValidationResult {
        errors = List.copyOf(errors);
    }

    /** Indica si el evento cumple el esquema OCSF. */
    public boolean isValid() {
        return errors.isEmpty();
    }

    /** Indica si el evento es de la clase OCSF indicada, p.ej. {@code is(DetectionFinding.class)}. */
    public boolean is(Class<?> type) {
        return javaType != null && type.isAssignableFrom(javaType);
    }
}
