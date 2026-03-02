![GitHub top Language](https://img.shields.io/github/languages/top/tfg-projects-dit-us/SOAR4FUEBA)
![GitHub forks](https://img.shields.io/github/forks/tfg-projects-dit-us/SOARFUEBA?style=social)
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

## Versión inicial

La versión inicial ha sido creada usando el arquetipo org.kie.kogito.kogito-springboot-archetype - 1.10.0.Final. Se ha incluido la dependencia de la librería para la gestión de openC2, que debe ser instalada previamente (como se indica en el apartado openC2)

## Ejecución

La ejecución del proyecto sería

- Compile and Run

    ```
    mvn clean package spring-boot:run    
    ```

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
