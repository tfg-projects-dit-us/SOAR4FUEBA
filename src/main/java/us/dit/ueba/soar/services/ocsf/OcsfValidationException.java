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

/**
 * El mensaje no cumple el esquema OCSF. La lanzan los métodos {@code require...} de {@link OcsfMessageValidator}.
 */
public class OcsfValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient OcsfValidationResult result;

    public OcsfValidationException(OcsfValidationResult result) {
        super("Mensaje OCSF no válido" + (result.className() == null ? "" : " (" + result.className() + ")") + ": "
                + String.join("; ", result.errors()));
        this.result = result;
    }

    /** Detalle de la validación, con todos los errores. */
    public OcsfValidationResult getResult() {
        return result;
    }
}
