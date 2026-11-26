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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jbpm.util.JsonSchemaUtil;
import org.kie.kogito.Model;
import org.kie.kogito.auth.IdentityProvider;
import org.kie.kogito.auth.IdentityProviderFactory;
import org.kie.kogito.internal.process.workitem.NotAuthorizedException;
import org.kie.kogito.process.Process;
import org.kie.kogito.process.Processes;
import org.kie.kogito.usertask.UserTaskInstanceNotAuthorizedException;
import org.kie.kogito.usertask.UserTaskInstanceNotFoundException;
import org.kie.kogito.usertask.UserTaskService;
import org.kie.kogito.usertask.lifecycle.UserTaskLifeCycleException;
import org.kie.kogito.usertask.lifecycle.UserTaskTransitionException;
import org.kie.kogito.usertask.view.UserTaskTransitionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * API REST de la consola SOC ({@code /soc/}): lista las tareas humanas pendientes y terminadas del usuario
 * autenticado y permite reclamarlas, liberarlas, guardar su resultado y completarlas.
 * <p>
 * Es una fachada sobre el {@link UserTaskService} de Kogito (el mismo que hay detrás de
 * {@code /usertasks/instance}) que además:
 * <ul>
 * <li>añade las tareas ya terminadas, que Kogito no conserva ({@link HistorialTareas});</li>
 * <li>añade las variables de la instancia del proceso, para dar contexto al analista;</li>
 * <li>describe los campos de salida de la tarea (a partir del JSON Schema que genera Kogito) para que la
 * página construya el formulario.</li>
 * </ul>
 * La identidad sale siempre de Spring Security: no se admite actuar en nombre de otro usuario.
 */
@RestController
@RequestMapping("/soc/api")
public class TareasSocController {

    private static final Logger LOG = LoggerFactory.getLogger(TareasSocController.class);

    /** Transiciones del ciclo de vida por defecto de Kogito que se ofrecen en la consola */
    private static final Set<String> TRANSICIONES = Set.of("claim", "release", "complete", "skip");

    private final UserTaskService tareas;
    private final IdentityProviderFactory identidades;
    private final HistorialTareas historial;
    private final Processes procesos;

    public TareasSocController(UserTaskService tareas, IdentityProviderFactory identidades, HistorialTareas historial,
            Processes procesos) {
        this.tareas = tareas;
        this.identidades = identidades;
        this.historial = historial;
        this.procesos = procesos;
    }

    /** Campo del formulario de una tarea: una variable de salida y su tipo JSON Schema. */
    public record CampoFormulario(String nombre, String tipo) {
    }

    /**
     * Detalle de una tarea.
     *
     * @param tarea         datos de la tarea
     * @param transiciones  transiciones que el usuario puede ejecutar ahora (vacía si la tarea ha terminado)
     * @param formulario    campos de salida que rellena el analista
     * @param datosProceso  variables de la instancia del proceso, o vacío si ya ha terminado
     * @param procesoActivo si la instancia del proceso sigue en curso
     */
    public record DetalleTarea(TareaSoc tarea, List<String> transiciones, List<CampoFormulario> formulario,
            Map<String, Object> datosProceso, boolean procesoActivo) {
    }

    /** Usuario autenticado y sus grupos. */
    public record Usuario(String usuario, List<String> grupos) {
    }

    @GetMapping("/yo")
    public Usuario yo() {
        IdentityProvider identidad = identidad();
        return new Usuario(identidad.getName(), List.copyOf(identidad.getRoles()));
    }

    /**
     * @param estado {@code pendientes} (por defecto) o {@code terminadas}
     */
    @GetMapping("/tareas")
    public List<TareaSoc> listar(@RequestParam(defaultValue = "pendientes") String estado) {
        IdentityProvider identidad = identidad();
        return switch (estado) {
            case "pendientes" -> tareas.list(identidad).stream()
                    .map(TareaSoc::de)
                    .filter(t -> !t.terminada())
                    .sorted(Comparator.comparing(TareaSoc::nombre, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(TareaSoc::id))
                    .toList();
            case "terminadas" -> historial.visiblesPara(identidad);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "estado debe ser 'pendientes' o 'terminadas'");
        };
    }

    @GetMapping("/tareas/{id}")
    public DetalleTarea detalle(@PathVariable String id) {
        return detalle(id, identidad());
    }

    /**
     * Guarda el resultado de la tarea sin completarla (por ejemplo, para seguir más tarde). Kogito solo lo permite
     * al propietario de la tarea.
     */
    @PutMapping("/tareas/{id}/salidas")
    public DetalleTarea guardarSalidas(@PathVariable String id, @RequestBody Map<String, Object> salidas) {
        IdentityProvider identidad = identidad();
        tareas.setOutputs(id, salidas, identidad).orElseThrow(() -> noEncontrada(id));
        return detalle(id, identidad);
    }

    /**
     * Ejecuta una transición del ciclo de vida de la tarea: {@code claim} (reclamar), {@code release} (liberar),
     * {@code complete} (completar, con las salidas en el cuerpo) o {@code skip} (omitir).
     */
    @PostMapping("/tareas/{id}/{transicion}")
    public DetalleTarea transicion(@PathVariable String id, @PathVariable String transicion,
            @RequestBody(required = false) Map<String, Object> datos) {
        if (!TRANSICIONES.contains(transicion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transición no soportada: " + transicion);
        }
        IdentityProvider identidad = identidad();
        LOG.info("{} ejecuta '{}' sobre la tarea {}", identidad.getName(), transicion, id);
        tareas.transition(id, transicion, datos == null ? Map.of() : datos, identidad)
                .orElseThrow(() -> noEncontrada(id));
        return detalle(id, identidad);
    }

    private DetalleTarea detalle(String id, IdentityProvider identidad) {
        Optional<TareaSoc> pendiente = tareas.getUserTaskInstance(id, identidad).map(TareaSoc::de);
        TareaSoc tarea = pendiente.or(() -> historial.buscar(id, identidad)).orElseThrow(() -> noEncontrada(id));
        List<String> transiciones = tarea.terminada() ? List.of()
                : tareas.allowedTransitions(id, identidad).stream()
                        .map(UserTaskTransitionView::getTransitionId)
                        .filter(TRANSICIONES::contains)
                        .toList();
        Optional<Map<String, Object>> variables = variablesProceso(tarea);
        return new DetalleTarea(tarea, transiciones, formulario(tarea), variables.orElse(Map.of()),
                variables.isPresent());
    }

    /** Variables de la instancia del proceso de la tarea, si sigue en curso. */
    private Optional<Map<String, Object>> variablesProceso(TareaSoc tarea) {
        if (tarea.procesoId() == null || tarea.instanciaProcesoId() == null) {
            return Optional.empty();
        }
        Process<? extends Model> proceso = procesos.processById(tarea.procesoId());
        if (proceso == null) {
            return Optional.empty();
        }
        return proceso.instances().findById(tarea.instanciaProcesoId())
                .map(instancia -> new LinkedHashMap<>(instancia.variables().toMap()));
    }

    /**
     * Campos de salida de la tarea. Kogito genera en el build un JSON Schema por tarea humana
     * ({@code META-INF/jsonSchema/<proceso>_<tarea>.json}) donde las salidas llevan {@code "output": true}.
     */
    @SuppressWarnings("unchecked")
    private List<CampoFormulario> formulario(TareaSoc tarea) {
        List<CampoFormulario> campos = new ArrayList<>();
        if (tarea.procesoId() == null || tarea.nombre() == null) {
            return campos;
        }
        try {
            Map<String, Object> esquema = JsonSchemaUtil.load(getClass().getClassLoader(), tarea.procesoId(),
                    tarea.nombre());
            Object propiedades = esquema == null ? null : esquema.get("properties");
            if (propiedades instanceof Map<?, ?> mapa) {
                ((Map<String, Object>) mapa).forEach((nombre, definicion) -> {
                    if (definicion instanceof Map<?, ?> d && Boolean.TRUE.equals(d.get("output"))) {
                        Object tipo = d.get("type");
                        campos.add(new CampoFormulario(nombre, tipo == null ? "string" : tipo.toString()));
                    }
                });
            }
        } catch (RuntimeException e) {
            LOG.warn("No se ha podido leer el esquema de la tarea {} del proceso {}: {}", tarea.nombre(),
                    tarea.procesoId(), e.getMessage());
        }
        return campos;
    }

    private IdentityProvider identidad() {
        // Sin usuario ni grupos explícitos devuelve la identidad autenticada en Spring Security
        return identidades.getOrImpersonateIdentity(null, null);
    }

    private static ResponseStatusException noEncontrada(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe la tarea " + id + " o no puede verla");
    }

    @ExceptionHandler(UserTaskInstanceNotFoundException.class)
    ProblemDetail noEncontrada(UserTaskInstanceNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({ UserTaskInstanceNotAuthorizedException.class, NotAuthorizedException.class })
    ProblemDetail noAutorizado(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler({ UserTaskTransitionException.class, UserTaskLifeCycleException.class })
    ProblemDetail transicionNoValida(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
