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

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración OCSF (prefijo {@code ocsf} en application.properties).
 * Las mismas propiedades las lee el build en src/codegen/ocsf-schemas.groovy.
 *
 * @param classes clases OCSF de interés (p.ej. detection_finding)
 * @param schema  de dónde se obtienen los esquemas
 */
@ConfigurationProperties(prefix = "ocsf")
public record OcsfProperties(@DefaultValue List<String> classes, @DefaultValue Schema schema) {

    /**
     * @param serverUrl       servidor OCSF del que se descargan los esquemas que no vengan en el classpath
     * @param downloadTimeout tiempo máximo de descarga de cada esquema
     * @param cacheDir        directorio donde se guardan los esquemas descargados al arrancar
     *                        (por defecto ${java.io.tmpdir}/soar4fueba/ocsf-schema)
     */
    public record Schema(
            @DefaultValue("https://schema.ocsf.io") URI serverUrl,
            @DefaultValue("PT10M") Duration downloadTimeout,
            Path cacheDir) {

        public Schema {
            if (cacheDir == null) {
                cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "soar4fueba", "ocsf-schema");
            }
        }
    }
}
