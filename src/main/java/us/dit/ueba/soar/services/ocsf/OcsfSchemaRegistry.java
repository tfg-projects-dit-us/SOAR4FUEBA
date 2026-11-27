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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.NonValidationKeyword;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Registro de los JSON Schema de las clases OCSF configuradas en {@code ocsf.classes}.
 * <p>
 * Al arrancar busca cada esquema, por orden, en:
 * <ol>
 * <li>el classpath ({@value #CLASSPATH_LOCATION}), donde los deja el build;</li>
 * <li>el directorio de caché {@code ocsf.schema.cache-dir};</li>
 * <li>el servidor {@code ocsf.schema.server-url}. Esta descarga se hace en segundo plano para no
 * bloquear el arranque (el servidor público tarda alrededor de un minuto por clase).</li>
 * </ol>
 * Las clases que solo se obtienen al arrancar se pueden validar, pero no tienen POJO generado:
 * para eso hay que recompilar.
 */
@Component
@EnableConfigurationProperties(OcsfProperties.class)
public class OcsfSchemaRegistry {

    static final String CLASSPATH_LOCATION = "ocsf/schema/classes/";

    private static final Logger LOG = LoggerFactory.getLogger(OcsfSchemaRegistry.class);

    /** @param javaType POJO generado para la clase, o {@code null} si no lo hay (esquema obtenido al arrancar) */
    private record Entry(String name, JsonSchema schema, Class<?> javaType) {
    }

    // Los esquemas OCSF son draft-07 pero agrupan los objetos en "$defs" (palabra clave de 2019-09):
    // se declara para que el validador no avise de que la desconoce. Las $ref se resuelven igual.
    private static final JsonMetaSchema OCSF_META_SCHEMA = JsonMetaSchema.builder(JsonMetaSchema.getV7())
            .keyword(new NonValidationKeyword("$defs"))
            .build();

    private final OcsfProperties properties;
    private final ObjectMapper mapper;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7,
            builder -> builder.metaSchema(OCSF_META_SCHEMA));
    private final Map<Integer, Entry> byClassUid = new ConcurrentHashMap<>();
    private final ExecutorService downloader = Executors.newVirtualThreadPerTaskExecutor();
    private volatile CompletableFuture<Void> downloads = CompletableFuture.completedFuture(null);

    public OcsfSchemaRegistry(OcsfProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @PostConstruct
    void load() {
        List<String> pending = new ArrayList<>();
        for (String name : properties.classes()) {
            JsonNode schema = fromClasspath(name);
            if (schema == null) {
                schema = fromCache(name);
            }
            if (schema != null) {
                register(name, schema);
            } else {
                pending.add(name);
            }
        }
        LOG.info("OCSF: {} clases cargadas {}", byClassUid.size(), loadedClasses());
        if (!pending.isEmpty()) {
            LOG.info("OCSF: descargando en segundo plano {} de {}", pending, properties.schema().serverUrl());
            downloads = CompletableFuture.allOf(pending.stream()
                    .map(name -> CompletableFuture.runAsync(() -> download(name), downloader)
                            .exceptionally(e -> {
                                LOG.error("OCSF: no se pudo descargar el esquema de {}", name, e);
                                return null;
                            }))
                    .toArray(CompletableFuture[]::new));
        }
    }

    @PreDestroy
    void shutdown() {
        downloader.shutdownNow();
    }

    /**
     * Valida un evento OCSF contra el esquema de su clase ({@code class_uid}).
     *
     * @return los errores de validación (vacío si el evento es válido)
     * @throws IllegalArgumentException si el evento no tiene class_uid o su clase no está registrada
     *                                  (ver {@link #isSupported(int)})
     */
    public Set<ValidationMessage> validate(JsonNode event) {
        JsonNode classUid = event.path("class_uid");
        if (!classUid.canConvertToInt()) {
            throw new IllegalArgumentException("El evento no tiene un class_uid OCSF");
        }
        Entry entry = byClassUid.get(classUid.asInt());
        if (entry == null) {
            throw new IllegalArgumentException("Clase OCSF " + classUid.asInt() + " no registrada; cargadas: "
                    + loadedClasses());
        }
        return entry.schema().validate(event);
    }

    /** Indica si hay esquema para la clase OCSF indicada (p.ej. 2004 = detection_finding). */
    public boolean isSupported(int classUid) {
        return byClassUid.containsKey(classUid);
    }

    /**
     * POJO generado en el build para la clase OCSF indicada (p.ej. 2004 → {@code DetectionFinding}).
     *
     * @return vacío si la clase no está registrada o no tiene POJO (su esquema se obtuvo al arrancar, sin recompilar)
     */
    public Optional<Class<?>> javaType(int classUid) {
        return Optional.ofNullable(byClassUid.get(classUid)).map(Entry::javaType);
    }

    /** Nombres de las clases OCSF cargadas, por class_uid. */
    public Map<Integer, String> loadedClasses() {
        Map<Integer, String> names = new TreeMap<>();
        byClassUid.forEach((uid, entry) -> names.put(uid, entry.name()));
        return names;
    }

    /** Se completa cuando terminan las descargas lanzadas al arrancar (útil en tests). */
    public CompletableFuture<Void> downloads() {
        return downloads;
    }

    private void register(String name, JsonNode schema) {
        JsonNode classUid = schema.at("/properties/class_uid/const");
        if (!classUid.canConvertToInt()) {
            LOG.warn("OCSF: el esquema de {} no declara class_uid; se ignora", name);
            return;
        }
        byClassUid.put(classUid.asInt(), new Entry(name, factory.getSchema(schema), javaTypeOf(name)));
    }

    /**
     * POJO de una clase OCSF. El build lo genera como {@code <ocsf.java-package>.<NombreEnCamelCase>}
     * (detection_finding → DetectionFinding), igual que src/codegen/ocsf-schemas.groovy.
     */
    private Class<?> javaTypeOf(String name) {
        String camel = Arrays.stream(name.split("_"))
                .filter(part -> !part.isEmpty())
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .collect(Collectors.joining());
        String className = properties.javaPackage() + "." + camel;
        try {
            return Class.forName(className, false, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            LOG.info("OCSF: {} no tiene POJO ({}); se podrá validar, pero no convertir a una clase Java", name,
                    className);
            return null;
        }
    }

    private JsonNode fromClasspath(String name) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CLASSPATH_LOCATION + name + ".json")) {
            return in == null ? null : mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Esquema OCSF ilegible en el classpath: " + name, e);
        }
    }

    private JsonNode fromCache(String name) {
        Path file = cacheFile(name);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            LOG.warn("OCSF: esquema en caché ilegible ({}); se descargará de nuevo", file, e);
            return null;
        }
    }

    private void download(String name) {
        OcsfProperties.Schema config = properties.schema();
        URI uri = config.serverUrl().resolve("/schema/classes/" + name);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .timeout(config.downloadTimeout())
                .GET().build();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " en " + uri);
            }
            JsonNode schema = mapper.readTree(response.body());
            Path file = cacheFile(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, response.body());
            register(name, schema);
            LOG.info("OCSF: esquema de {} descargado y registrado", name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Descarga de " + name + " interrumpida", e);
        }
    }

    private Path cacheFile(String name) {
        return properties.schema().cacheDir().resolve(name + ".json");
    }
}
