# Mensajes OCSF en SOAR4FUEBA

## Qué es OCSF

[OCSF](https://ocsf.io) (*Open Cybersecurity Schema Framework*) es un estándar abierto para representar eventos de seguridad. Lo impulsaron AWS y Splunk, entre otros, y hoy lo mantiene la Linux Foundation. Define un vocabulario común organizado en:

- **Categorías**, por ejemplo *System Activity*, *Findings* o *Identity & Access Management*.
- **Clases de evento** dentro de cada categoría. Cada clase tiene un identificador numérico (`class_uid`); por ejemplo, `detection_finding` es la 2004.
- **Objetos** reutilizables entre clases, como `device`, `user`, `file` o `finding_info`.
- **Perfiles y extensiones**, que añaden atributos para contextos concretos (cloud, host, OSINT...) o propios de un fabricante.

Todo el esquema se publica en [schema.ocsf.io](https://schema.ocsf.io), y desde ahí se puede descargar cada clase en formato JSON Schema.

## Ventajas

- **Formato común:** EDR, SIEM, firewalls y servicios cloud pueden emitir el mismo formato, y el SOAR no necesita un parser por cada fuente.
- **Semántica precisa:** los campos `*_id` son enumerados con significado fijo (`severity_id`, `status_id`...), y el evento siempre lleva su clase (`class_uid`) y su tipo (`type_uid`).
- **Validación automática:** como hay un JSON Schema oficial, se puede comprobar si un mensaje es correcto antes de procesarlo.
- **Ecosistema:** lo usan ya productos como AWS Security Lake, Splunk o CrowdStrike, y hay herramientas oficiales de traducción y validación.
- **Extensible:** perfiles y extensiones permiten añadir información sin romper la compatibilidad.

## Cómo se gestiona OCSF en esta versión

Ahora mismo se trabaja con la **categoría 2, *Findings***. Son las clases de hallazgos de seguridad, que es lo que recibe el SOAR:

| Clase | `class_uid` | Clase Java generada |
|---|---|---|
| `vulnerability_finding` | 2002 | `VulnerabilityFinding` |
| `compliance_finding` | 2003 | `ComplianceFinding` |
| `detection_finding` | 2004 | `DetectionFinding` |
| `incident_finding` | 2005 | `IncidentFinding` |
| `data_security_finding` | 2006 | `DataSecurityFinding` |
| `application_security_posture_finding` | 2007 | `ApplicationSecurityPostureFinding` |
| `iam_analysis_finding` | 2008 | `IamAnalysisFinding` |

`security_finding` (2001) no se incluye porque OCSF la ha declarado obsoleta.

### Configuración

Todo se controla desde `src/main/resources/application.properties`:

```properties
ocsf.classes=vulnerability_finding,compliance_finding,detection_finding,...
ocsf.schema.server-url=https://schema.ocsf.io
ocsf.schema.download-timeout=PT10M
# Opcional: dónde se guardan los esquemas descargados al arrancar
# ocsf.schema.cache-dir=/ruta/cache
```

El build lee estas propiedades directamente del fichero, así que deben tener **valores literales**, sin `${...}`.

### En el build (`mvn compile`)

1. El script [src/codegen/ocsf-schemas.groovy](src/codegen/ocsf-schemas.groovy), que se ejecuta con `gmavenplus-plugin`, lee `ocsf.classes`.
2. Descarga de `https://schema.ocsf.io/schema/classes/<clase>` el JSON Schema de cada clase que no esté ya en `src/main/resources/ocsf/schema/classes/`. También guarda en `version.json` la versión de OCSF descargada.
   - El servidor público tarda alrededor de un minuto por clase. Por eso los esquemas se guardan en el proyecto y **conviene subirlos a git**: así los siguientes builds no necesitan red.
3. El script prepara en `target/ocsf-codegen/` una copia adaptada para generar código:
   - Separa cada objeto en su propio fichero, para que se genere una sola clase Java aunque varias clases OCSF lo usen.
   - Quita los enumerados y las restricciones, para que los POJOs acepten cualquier evento bien formado. Las restricciones se comprueban después, al validar.
   - El objeto genérico `object` de OCSF (p.ej. `unmapped`) se representa como `Map<String,Object>`.
4. `jsonschema2pojo` genera los POJOs Jackson en el paquete `us.dit.ueba.soar.ocsf`, dentro de `target/generated-sources/jsonschema2pojo`. Detalles de la generación:
   - Los enteros son `Long`, porque los tiempos OCSF son epoch en milisegundos.
   - Los campos `*_id` son numéricos, no enums Java.
   - Cada clase guarda en `getAdditionalProperties()` los atributos que no están en el esquema, como los de extensiones de fabricante.
   - Cada clase tiene métodos `withXxx()` para construir eventos encadenando llamadas.

Las clases generadas no se guardan en git: se crean en cada build. Si el IDE marca `us.dit.ueba.soar.ocsf` como paquete inexistente, ejecuta `mvn compile` y recarga el proyecto Java (en VS Code: *Java: Clean Java Language Server Workspace*).

### Al arrancar la aplicación

[OcsfSchemaRegistry](src/main/java/us/dit/ueba/soar/services/ocsf/OcsfSchemaRegistry.java) carga el esquema de cada clase de `ocsf.classes`. Lo busca primero en el classpath, después en la caché (`ocsf.schema.cache-dir`) y, si no está en ninguno, lo descarga del servidor en segundo plano para no retrasar el arranque.

### Uso desde el código

Para validar mensajes se usa [OcsfMessageValidator](src/main/java/us/dit/ueba/soar/services/ocsf/OcsfMessageValidator.java), un bean común a todas las clases OCSF: deduce la clase del `class_uid` del evento. Se inyecta ya configurado, **no hay que registrar ningún esquema**: `OcsfSchemaRegistry` carga los de `ocsf.classes` al arrancar. Acepta el mensaje tal como llega de Kafka (texto JSON), suelto o dentro de un CloudEvent (valida su `data`), y también `JsonNode`, `Map` o un POJO generado.

```java
@Service
public class MiServicio {

    private final OcsfMessageValidator validador;

    public MiServicio(OcsfMessageValidator validador) {   // inyección de Spring
        this.validador = validador;
    }

    void procesar(String mensaje) {
        // 1. Resultado con todos los errores, sin excepciones
        OcsfValidationResult r = validador.validate(mensaje);
        if (!r.isValid()) {
            log.warn("Mensaje {} no válido: {}", r.className(), r.errors());
        }

        // 2. Solo sí/no (p.ej. tarea de servicio seguida de una puerta de enlace en el BPMN)
        boolean ok = validador.isValid(mensaje);

        // 3. Obtener el evento o su POJO; lanza OcsfValidationException si no es válido
        JsonNode evento = validador.requireValid(mensaje);
        DetectionFinding finding = validador.readValid(mensaje, DetectionFinding.class);

        // 4. Actuar según el tipo de mensaje recibido
        if (r.isValid()) {
            switch (validador.convert(r)) {          // convierte al POJO de su clase (r.javaType())
                case DetectionFinding d -> ...
                case IncidentFinding i -> ...
                default -> ...
            }
        }
    }
}
```

El resultado indica el tipo de mensaje: `classUid()` (2004), `className()` (`detection_finding`) y `javaType()` (`DetectionFinding.class`). `javaType()` es `null` si la clase no tiene POJO porque su esquema se descargó al arrancar sin recompilar; en ese caso se puede actuar según `className()` y trabajar con `event()`. El paquete de los POJOs es `ocsf.java-package` (por defecto `us.dit.ueba.soar.ocsf`), que debe coincidir con `ocsf.java.package` del pom.

Un mensaje que no es JSON, que no tiene `class_uid` o cuya clase no está en `ocsf.classes` se considera no válido y el motivo aparece en `errors()`.

> **Pendiente:** la validación todavía no está conectada a la recepción de mensajes Kafka del proceso BPMN. Falta decidir qué hacer con un mensaje inválido: descartarlo, registrarlo o enviarlo a un topic de errores.

## Cómo añadir otros mensajes OCSF

### Añadir una clase con su POJO (lo habitual)

1. Busca el nombre de la clase en [schema.ocsf.io](https://schema.ocsf.io/categories), por ejemplo `authentication` (3002) o `process_activity` (1007).
2. Añádela a `ocsf.classes` en `application.properties`.
3. Ejecuta `mvn compile`. Se descargará solo el esquema nuevo y se generará la clase, por ejemplo `us.dit.ueba.soar.ocsf.Authentication`.
4. Sube a git el nuevo fichero de `src/main/resources/ocsf/schema/classes/`.

### Validar una clase sin recompilar

Si añades la clase a `ocsf.classes` en la configuración del despliegue (variable de entorno, fichero externo...) sin recompilar, `OcsfSchemaRegistry` la descarga al arrancar y la puede **validar**. Pero **no habrá POJO** para ella: tendrás que tratarla como `JsonNode` o `Map` hasta el siguiente build.

### Actualizar la versión de OCSF

El servidor siempre sirve su versión por defecto, que es la que indica `/api/version`. Para volver a descargar todos los esquemas con la versión actual:

```
mvn compile -Docsf.refresh=true
```

Si el servidor ha cambiado de versión, el build avisa de que los esquemas guardados son de otra versión. Antes de subir los esquemas actualizados, revisa sus diferencias en git: un cambio de versión puede renombrar o eliminar atributos.

### Perfiles y extensiones

Los esquemas se descargan sin seleccionar perfiles, así que incluyen los atributos de todos. Los atributos de extensiones de fabricante no aparecen en el esquema: en los POJOs quedan en `getAdditionalProperties()`, y la validación los marcará como no permitidos, porque el esquema oficial declara `additionalProperties: false`.
