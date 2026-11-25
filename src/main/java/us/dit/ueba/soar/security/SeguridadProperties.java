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
package us.dit.ueba.soar.security;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Usuarios de prueba definidos en application.properties ({@code soar.security.users.<usuario>.*}).
 * <p>
 * Es una solución provisional hasta conectar un proveedor de identidad real (Keycloak/OIDC, LDAP...).
 *
 * @param users usuarios indexados por su nombre
 */
@ConfigurationProperties(prefix = "soar.security")
public record SeguridadProperties(@DefaultValue Map<String, Usuario> users) {

    /**
     * @param password contraseña con el prefijo de su codificación: {@code {noop}texto} o {@code {bcrypt}hash}
     * @param groups   grupos del usuario. Son los que se comparan con los GroupId de las tareas humanas
     */
    public record Usuario(String password, @DefaultValue List<String> groups) {
    }
}
