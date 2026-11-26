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

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad de la API REST (procesos, tareas humanas, decisiones...).
 * <p>
 * Kogito no autentica por sí mismo: su {@code SpringIdentityProvider} toma el usuario y sus grupos del
 * contexto de Spring Security. Con {@code kogito.security.auth.enabled=true} esa es la identidad con la que se
 * listan, reclaman y completan las tareas humanas.
 * <p>
 * <b>Provisional:</b> los usuarios se leen de application.properties ({@link SeguridadProperties}) y se
 * autentican con HTTP Basic. Para conectar un proveedor real basta con sustituir {@link #usuarios} y
 * {@code httpBasic} (por ejemplo, {@code oauth2ResourceServer(o -> o.jwt(...))} para Keycloak/OIDC); el resto de
 * la aplicación no cambia.
 */
@Configuration
@EnableConfigurationProperties(SeguridadProperties.class)
public class SeguridadConfig {

    @Bean
    SecurityFilterChain seguridad(HttpSecurity http) throws Exception {
        return http
                // API REST sin sesiones ni formularios: CSRF no aplica
                .csrf(AbstractHttpConfigurer::disable)
                // Usa el CorsConfigurationSource de CorsConfig. Así el preflight OPTIONS se responde antes de
                // exigir credenciales
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(peticiones -> peticiones
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Consola SOC: la página (HTML, JS, CSS) es pública; los datos se piden a /soc/api con
                        // las credenciales que introduce el usuario en su pantalla de acceso
                        .requestMatchers("/soc/api/**").authenticated()
                        .requestMatchers("/soc", "/soc/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    /**
     * Usuarios en memoria. Sus grupos se cargan como <i>authorities</i> sin el prefijo {@code ROLE_}: Kogito los
     * usa tal cual, y deben coincidir con los GroupId de las tareas del BPMN (p.ej. {@code soc}).
     */
    @Bean
    UserDetailsService usuarios(SeguridadProperties propiedades) {
        List<UserDetails> usuarios = propiedades.users().entrySet().stream()
                .map(e -> User.withUsername(e.getKey())
                        .password(e.getValue().password())
                        .authorities(e.getValue().groups().toArray(String[]::new))
                        .build())
                .toList();
        return new InMemoryUserDetailsManager(usuarios);
    }
}
