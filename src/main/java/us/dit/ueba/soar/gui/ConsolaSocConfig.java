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

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuración de la consola SOC (interfaz web para gestionar las tareas humanas).
 * <p>
 * La página está en {@code src/main/resources/META-INF/resources/soc} y se sirve en {@code /soc/}; su API
 * REST es {@link TareasSocController}.
 * <p>
 * No hace falta registrar {@link HistorialTareas} en Kogito: el código que genera Kogito
 * ({@code UserTaskServiceProducer}) añade todos los beans {@code UserTaskEventListener}. Ojo: no hay que declarar
 * otro bean {@code UserTaskEventListenerConfig}, porque Kogito usaría solo uno de ellos y podría perder el listener
 * que devuelve al proceso el resultado de las tareas.
 */
@Configuration
public class ConsolaSocConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/soc", "/soc/index.html");
        registry.addRedirectViewController("/soc/", "/soc/index.html");
    }
}
