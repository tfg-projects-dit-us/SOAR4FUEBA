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

/*
 * Script de build (gmavenplus, fase generate-sources) que prepara los esquemas OCSF.
 *
 * 1. Lee de src/main/resources/application.properties:
 *      ocsf.classes             -> clases OCSF de interés (lista separada por comas)
 *      ocsf.schema.server-url   -> servidor OCSF (por defecto https://schema.ocsf.io)
 * 2. Descarga el JSON Schema de cada clase que no esté ya en
 *    src/main/resources/ocsf/schema/classes/<clase>.json (el servidor tarda ~1 min por clase,
 *    por eso se guardan y conviene versionarlos en git). Con -Docsf.refresh=true se vuelven a bajar todos.
 * 3. Prepara en target/ocsf-codegen una copia adaptada para jsonschema2pojo:
 *      - un fichero por objeto ($defs) para que cada objeto genere una única clase compartida
 *      - javaType explícito en cada clase/objeto
 *      - sin enums, anyOf/oneOf/not ni additionalProperties=false, para que los POJOs sean tolerantes
 *        (las restricciones se comprueban en tiempo de ejecución con el esquema original)
 *
 * Variables disponibles (gmavenplus): project, session, log
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

final Path baseDir = project.basedir.toPath()
final Path appPropsFile = baseDir.resolve('src/main/resources/application.properties')
final Path schemaDir = baseDir.resolve('src/main/resources/ocsf/schema')
final Path classesDir = schemaDir.resolve('classes')
final Path codegenDir = Path.of(project.build.directory, 'ocsf-codegen')
final String javaPackage = project.properties['ocsf.java.package']

// Permite sobrescribir cualquier valor con -D<propiedad>=<valor>
def appProps = new Properties()
Files.newBufferedReader(appPropsFile, StandardCharsets.UTF_8).withCloseable { appProps.load(it) }
def prop = { String key, String dflt = null ->
    def value = session.userProperties.getProperty(key) ?: appProps.getProperty(key) ?: dflt
    if (value?.contains('${')) {
        throw new IllegalStateException("La propiedad ${key} usa placeholders (${value}); el build necesita un valor literal")
    }
    value
}

final List<String> classes = (prop('ocsf.classes', '')).split(',')*.trim().findAll { it }
final String serverUrl = prop('ocsf.schema.server-url', 'https://schema.ocsf.io').replaceAll('/+$', '')
final Duration timeout = Duration.parse(prop('ocsf.schema.download-timeout', 'PT10M'))
final boolean refresh = Boolean.parseBoolean(prop('ocsf.refresh', 'false'))

if (classes.isEmpty()) {
    log.warn('[OCSF] ocsf.classes está vacío: no se generan clases OCSF')
    return
}
log.info("[OCSF] Clases de interés: ${classes}")

// ---------------------------------------------------------------------------------------------
// 1. Descarga de los esquemas que falten
// ---------------------------------------------------------------------------------------------
Files.createDirectories(classesDir)
def missing = classes.findAll { refresh || !Files.exists(classesDir.resolve("${it}.json")) }

if (missing) {
    if (session.offline) {
        throw new IllegalStateException("[OCSF] Modo offline y faltan esquemas: ${missing}")
    }
    def http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    def fetch = { String url ->
        def request = HttpRequest.newBuilder(URI.create(url))
                .header('Accept', 'application/json')
                .timeout(timeout)
                .GET().build()
        def response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() != 200) {
            throw new IOException("HTTP ${response.statusCode()} en ${url}")
        }
        new JsonSlurperClassic().parseText(response.body()) // falla si no es JSON (p.ej. página HTML)
    }

    def serverVersion = fetch("${serverUrl}/api/version").version
    def versionFile = schemaDir.resolve('version.json')
    if (Files.exists(versionFile)) {
        def previous = new JsonSlurperClassic().parse(versionFile.toFile()).version
        if (previous != serverVersion && !refresh) {
            log.warn("[OCSF] Los esquemas ya descargados son de la versión ${previous} y el servidor está en " +
                    "${serverVersion}. Usa -Docsf.refresh=true para descargarlos todos de nuevo.")
        }
    }

    log.info("[OCSF] Descargando ${missing} de ${serverUrl} (OCSF ${serverVersion}); puede tardar varios minutos...")
    def pool = Executors.newFixedThreadPool(Math.min(4, missing.size()))
    try {
        def futures = missing.collectEntries { name ->
            [(name): pool.submit({
                def schema = fetch("${serverUrl}/schema/classes/${name}")
                Files.writeString(classesDir.resolve("${name}.json"), JsonOutput.prettyPrint(JsonOutput.toJson(schema)))
                log.info("[OCSF]   ${name} descargada")
            } as Runnable)]
        }
        def errors = futures.findResults { name, future ->
            try { future.get(); null } catch (Exception e) { "${name}: ${e.cause ?: e}" }
        }
        if (errors) {
            throw new IllegalStateException("[OCSF] Error descargando esquemas:\n  " + errors.join('\n  '))
        }
    } finally {
        pool.shutdownNow()
    }

    Files.writeString(versionFile, JsonOutput.prettyPrint(JsonOutput.toJson([
            server    : serverUrl,
            version   : serverVersion,
            downloaded: Instant.now().toString()])))
}

// ---------------------------------------------------------------------------------------------
// 2. Copia adaptada para jsonschema2pojo
// ---------------------------------------------------------------------------------------------
final String DEFS_PREFIX = '#/$defs/'
final String FREE_OBJECT = 'object'   // objeto OCSF genérico: se mapea a Map, no a una clase "Object"

def camel = { String name -> name.split('_').collect { it.capitalize() }.join() }

// Recorre el esquema adaptándolo. Las claves de "properties" son nombres de atributo, no palabras clave.
def adapt
adapt = { node, boolean isPropertiesMap = false ->
    if (node instanceof Map) {
        if (!isPropertiesMap) {
            def ref = node['$ref']
            if (ref == DEFS_PREFIX + FREE_OBJECT) {
                node.remove('$ref')
                node.type = 'object'
                node.existingJavaType = 'java.util.Map<String,Object>'
            } else if (ref instanceof String && ref.startsWith(DEFS_PREFIX)) {
                node['$ref'] = ref.substring(DEFS_PREFIX.length()) + '.json'
            }
            ['enum', 'anyOf', 'oneOf', 'not', '$id', '$schema'].each { node.remove(it) }
            if (node.additionalProperties == false) {
                node.remove('additionalProperties')
            }
        }
        node.each { key, value -> adapt(value, !isPropertiesMap && key == 'properties') }
    } else if (node instanceof List) {
        node.each { adapt(it) }
    }
    node
}

def write = { String name, Map schema ->
    Files.writeString(codegenDir.resolve("${name}.json"), JsonOutput.toJson(adapt(schema)))
}

codegenDir.toFile().deleteDir()
Files.createDirectories(codegenDir)

def defs = new TreeMap<String, Map>()
classes.each { name ->
    def schema = new JsonSlurperClassic().parse(classesDir.resolve("${name}.json").toFile())
    (schema.remove('$defs') ?: [:]).each { defName, def_ -> defs.putIfAbsent(defName, def_) }
    schema.javaType = "${javaPackage}.${camel(name)}"
    write(name, schema)
}
defs.remove(FREE_OBJECT)
defs.each { name, schema ->
    schema.javaType = "${javaPackage}.${camel(name)}"
    write(name, schema)
}
log.info("[OCSF] Preparados ${classes.size()} clases y ${defs.size()} objetos para generar en ${javaPackage}")
