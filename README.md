![GitHub top Language](https://img.shields.io/github/languages/top/tfg-projects-dit-us/SOAR4FUEBA)
![GitHub forks](https://img.shields.io/github/forks/tfg-projects-dit-us/SOAR4FUEBA?style=social)
![GitHub contributors](https://img.shields.io/github/contributors/tfg-projects-dit-us/SOAR4FUEBA)
![GitHub Repo stars](https://img.shields.io/github/stars/tfg-projects-dit-us/SOAR4FUEBA?style=social)
![GitHub repo size](https://img.shields.io/github/repo-size/tfg-projects-dit-us/SOAR4FUEBA)
![GitHub watchers](https://img.shields.io/github/watchers/tfg-projects-dit-us/SOAR4FUEBA)


# SOAR4FUEBA

## Introducción
El proyecto SOAR4FUEBA es una solución para la Orquestación, automatización y respuesta de seguridad (SOAR) que utiliza los estándares del OMG (BPMN y DMN) para la orquestación y automatización de procesos de seguridad en una organización. 

Está desarrollada en el marco del proyecto de investigación PID2024-155581OB-C21 – Forensic UEBA: Detección temprana de ciberataques con custodia forense de evidencias digitales en entornos corporativos (FUEBA+)

## Estándares utilizados

### OpenC2

Las tareas de servicio que requieran la ejecución de comandos en máquinas remotas utilizarán el estándar openc2 para comandar las instrucciones. Se reutiliza el código disponible en https://github.com/oasis-open/openc2-lycan-java, para ello se ha creado el fork https://github.com/tfg-projects-dit-us/openc2-lycan-java, donde se actualiza la versión de java y otras librerías.
Dado que este proyecto no está en los repositorios abiertos de maven es necesario clonarlo de https://github.com/tfg-projects-dit-us/openc2-lycan-java e instalarlo en el repositorio maven central con el comando
 
    ```
    mvn clean install    
    ```
A partir de ese momento podrá incluir la dependencia en su proyecto usando 

    ```
    <dependency>
      <groupId>org.oasis.openc2</groupId>
      <artifactId>lycan</artifactId>
      <version>1.0.0</version>
    </dependency>
    
    ```
    En este proyecto esa dependencia ya está incluida, por lo que no es necesario modificar el pom

### BPMN y DMN
Los procesos se especifican usando la notación formal BPMN
Las reglas se especifican usando la notación formal DMN

### OCSF
Los mensajes de seguridad que se envían y reciben por Kafka siguen el estándar [OCSF](https://ocsf.io) (*Open Cybersecurity Schema Framework*). Las clases Java de los mensajes se generan en el build a partir de los esquemas oficiales, y los mensajes se pueden validar contra esos esquemas al ejecutar. Se explica en [ocsf.md](ocsf.md) y en el apartado [Ejecución](#ejecución).

## Versión inicial

La versión inicial ha sido creada usando el arquetipo org.kie.kogito.kogito-springboot-archetype - 1.10.0.Final. Se ha incluido la dependencia de la librería para la gestión de openC2, que debe ser instalada previamente (como se indica en el apartado openC2)

## Actualización del **24 de noviembre de 2026**
### Migración del proyecto a versiones actuales de los frameworks utilizados

| Componente  | Versión anterior | Versión nueva |
|-------------|------------------|---------------|
| Java        | 11               | 21            |
| Spring Boot | 2.3.10.RELEASE   | 3.5.10        |
| Kogito      | 1.10.0.Final     | 10.2.0 (Apache KIE) |

> Inicialmente se migró a Kogito 10.0.0 con Spring Boot 3.2.10. Después se subió a Kogito 10.2.0 con Spring Boot 3.5.10, la versión de Spring Boot con la que está construido Kogito 10.2.0. Así el motor queda alineado con la versión 10.2.0 de la extensión de VS Code *Apache KIE BPMN Editor* que se usa para modelar los procesos.

Cambios realizados:

- Se importa el BOM `org.kie.kogito:kogito-spring-boot-bom` en `dependencyManagement` para gestionar las versiones de Kogito.
- Se sustituye el antiguo `org.kie.kogito:kogito-springboot-starter`, que ya no existe en Kogito 10, por `org.jbpm:jbpm-with-drools-spring-boot-starter`. Este starter incluye el soporte de procesos (BPMN), decisiones (DMN), reglas (DRL) y predicciones (PMML).
- Se añade la dependencia `spring-boot-starter-webflux`.
- Desde Kogito 10.2.0, el `kogito-maven-plugin` ya no se engancha automáticamente al ciclo de vida de Maven. Por eso se declara explícitamente su goal `generateModel`, que se ejecuta en la fase `process-classes`. Sin él no se genera el código de los procesos y decisiones, y la aplicación no arranca (falta el bean `ConfigBean`).

### Se añade Kafka

Los procesos pueden recibir y enviar eventos a través de Kafka.

Dependencias añadidas:

| Dependencia | Versión | Gestionada por |
|-------------|---------|----------------|
| `org.kie:kie-addons-springboot-messaging` | 10.2.0 | `kogito-spring-boot-bom` |
| `org.springframework.kafka:spring-kafka` | 3.3.12 (kafka-clients 3.9.1) | `spring-boot-starter-parent` |

- El addon `kie-addons-springboot-messaging` conecta los eventos de mensaje BPMN con Kafka. Los eventos de recepción (inicio o intermedio) consumen de un topic y los eventos de envío (intermedio o fin) publican en un topic. Los eventos se intercambian en formato [CloudEvents](https://cloudevents.io/).
- La versión de `spring-kafka` no se fija en el pom, sino que la gestiona Spring Boot. Así se garantiza la compatibilidad con Spring y Kogito. Forzar un cliente Kafka 4.x podría provocar errores al ejecutar. El cliente 3.9 funciona con brokers Kafka modernos, incluido Kafka 4.x con KRaft.

Configuración en `src/main/resources/application.properties`:

```properties
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
spring.kafka.consumer.group-id=soar4fueba
spring.kafka.consumer.auto-offset-reset=earliest
```

- La dirección del broker se puede cambiar con la variable de entorno `KAFKA_BOOTSTRAP_SERVERS`.
- El `group-id` es obligatorio: sin él la aplicación no arranca.
- Por defecto, los mensajes se reciben del topic `kogito_incoming_stream` y se publican en `kogito_outgoing_stream`. Para asignar un topic concreto a cada mensaje BPMN:

```properties
kogito.addon.cloudevents.kafka.kogito_incoming_stream.<NombreMensaje>=<topic>
kogito.addon.cloudevents.kafka.kogito_outgoing_stream.<NombreMensaje>=<topic>
```

En los tests (`src/test/resources/application.properties`) se desactiva el arranque de los consumidores (`spring.kafka.listener.auto-startup=false`), de modo que las pruebas no necesitan un broker Kafka en ejecución.

## Ejecución

Para compilar y arrancar la aplicación:

```sh
mvn clean package spring-boot:run
```

### Esquemas OCSF: no hay que ejecutar nada antes

Los esquemas OCSF se preparan con el script [src/codegen/ocsf-schemas.groovy](src/codegen/ocsf-schemas.groovy), pero **no hay que lanzarlo a mano**. Maven lo ejecuta solo en la fase `generate-sources`, que forma parte de `compile`, `test` y `package`. Cada build hace lo siguiente, en orden:

1. **`generate-sources`:** el script lee `ocsf.classes` de `application.properties` y descarga de https://schema.ocsf.io el esquema de las clases que no estén ya en `src/main/resources/ocsf/schema/classes/`. Después, jsonschema2pojo genera las clases Java (`DetectionFinding`, `IncidentFinding`...) en `target/generated-sources/jsonschema2pojo`.
2. **`compile`:** se compila el código del proyecto junto con esas clases generadas.
3. **`process-classes`:** Kogito genera el código de los procesos BPMN y las decisiones DMN.
4. **`test` y `package`:** se ejecutan los tests y se empaqueta el jar.

Algunas consecuencias prácticas:

- **`clean` no borra los esquemas.** Los esquemas descargados están en `src/` y se guardan en git. `clean` solo borra `target/`, así que en cada build únicamente se regeneran las clases Java, lo que es rápido y no necesita red.
- **Hace falta conexión a Internet solo al descargar un esquema.** Eso ocurre la primera vez que se añade una clase a `ocsf.classes` o al forzar la actualización. El servidor tarda alrededor de un minuto por clase; las descargas se hacen en paralelo y el build muestra su progreso con mensajes `[OCSF]`.
- **Sin conexión se puede compilar con `-o`** (`mvn -o clean package`) siempre que los esquemas ya estén descargados. Si falta alguno, el build falla e indica cuál.
- **Para actualizar todos los esquemas** a la versión que publica ahora el servidor OCSF:

    ```sh
    mvn clean package -Docsf.refresh=true
    ```

    Revisa los cambios con `git diff src/main/resources/ocsf` antes de subirlos: una versión nueva de OCSF puede renombrar o eliminar atributos.

- **Para generar solo las clases OCSF**, sin compilar el resto ni ejecutar los tests: `mvn generate-sources`.
- **Si el IDE no reconoce las clases generadas** (`package us.dit.ueba.soar.ocsf does not exist`), ejecuta `mvn compile` y recarga el proyecto. En VS Code: *Java: Clean Java Language Server Workspace*.

Más detalles sobre OCSF en el proyecto en [ocsf.md](ocsf.md).

## Pruebas

La versión inicial, generada a través del arquetipo comes with sample test process that allows you to verify if the application is working as expected. Simply execute following command to try it out

```sh
curl -d '{}' -H "Content-Type: application/json" -X POST http://localhost:8080/greetings
                                                             
```

Once successfully invoked you should see "Hello World" in the console of the running application.

The generated application provides out of the box multiple samples of Kogito assets; you can reference the generated Swagger documentation and JUnit tests.

## Desarrollo

Add your business assets resources (process definition, rules, decisions) into src/main/resources.

Add your java classes (data model, utilities, services) into src/main/java.

Then just build the project and run.


## OpenAPI (Swagger) documentation
[Specification at swagger.io](https://swagger.io/docs/specification/about/)

You can take a look at the [OpenAPI definition](http://localhost:8080/v3/api-docs) - automatically generated and included in this service - to determine all available operations exposed by this service. For easy readability you can visualize the OpenAPI definition file using a UI tool like for example available [Swagger UI](https://editor.swagger.io).

In addition, various clients to interact with this service can be easily generated using this OpenAPI definition.
