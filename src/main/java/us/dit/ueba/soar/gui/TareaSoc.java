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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.kie.kogito.usertask.UserTaskInstance;
import org.kie.kogito.usertask.lifecycle.UserTaskState;
import org.kie.kogito.usertask.model.ProcessInfo;
import org.kie.kogito.usertask.view.UserTaskView;

/**
 * Tarea humana tal como la muestra la consola SOC. Sirve tanto para las tareas pendientes (que se leen de Kogito)
 * como para las terminadas (que guarda {@link HistorialTareas}, porque Kogito las borra al terminar).
 *
 * @param id                 identificador de la instancia de la tarea
 * @param nombre             TaskName del BPMN (p.ej. {@code RevisarIncidencia})
 * @param descripcion        descripción de la tarea, si la tiene
 * @param prioridad          prioridad de la tarea, si la tiene
 * @param estado             estado en el ciclo de vida de Kogito (Active, Reserved, Completed...)
 * @param terminada          si la tarea ha terminado (completada, omitida, obsoleta...)
 * @param propietario        usuario que la ha reclamado, o {@code null} si está libre
 * @param usuarios           usuarios potenciales
 * @param grupos             grupos potenciales (GroupId del BPMN)
 * @param procesoId          id del proceso BPMN al que pertenece
 * @param instanciaProcesoId id de la instancia del proceso
 * @param entradas           datos de entrada de la tarea (sin los parámetros técnicos de Kogito)
 * @param salidas            datos de salida de la tarea
 * @param fechaFin           momento en que terminó, o {@code null} si está pendiente
 * @param cerradaPor         usuario que la terminó, o {@code null} si está pendiente
 */
public record TareaSoc(String id, String nombre, String descripcion, String prioridad, String estado,
        boolean terminada, String propietario, Set<String> usuarios, Set<String> grupos, String procesoId,
        String instanciaProcesoId, Map<String, Object> entradas, Map<String, Object> salidas, Instant fechaFin,
        String cerradaPor) {

    /** Parámetros que Kogito añade a las entradas de toda tarea humana y que no interesan al analista */
    private static final Set<String> ENTRADAS_TECNICAS = Set.of("TaskName", "GroupId", "ActorId", "NodeName",
            "Skippable", "Priority", "Description", "BusinessAdministratorId", "BusinessAdministratorGroupId",
            "ExcludedOwnerId", "Content", "Locale", "CreatedBy", "UNIQUE_TASK_ID");

    /** Tarea pendiente, a partir de la vista que devuelve el {@code UserTaskService} de Kogito. */
    static TareaSoc de(UserTaskView t) {
        ProcessInfo p = t.getProcessInfo();
        return new TareaSoc(t.getId(), t.getTaskName(), t.getTaskDescription(), t.getTaskPriority(),
                nombre(t.getStatus()), terminada(t.getStatus()), t.getActualOwner(), copia(t.getPotentialUsers()),
                copia(t.getPotentialGroups()), p == null ? null : p.getProcessId(),
                p == null ? null : p.getProcessInstanceId(), entradas(t.getInputs()), copia(t.getOutputs()), null,
                null);
    }

    /** Tarea terminada: foto de la instancia en el momento en que Kogito notifica su fin. */
    static TareaSoc terminada(UserTaskInstance t, UserTaskState estado, Instant fecha, String usuario) {
        ProcessInfo p = t.getProcessInfo();
        return new TareaSoc(t.getId(), t.getTaskName(), t.getTaskDescription(), t.getTaskPriority(), nombre(estado),
                true, t.getActualOwner(), copia(t.getPotentialUsers()), copia(t.getPotentialGroups()),
                p == null ? null : p.getProcessId(), p == null ? null : p.getProcessInstanceId(),
                entradas(t.getInputs()), copia(t.getOutputs()), fecha, usuario);
    }

    private static String nombre(UserTaskState estado) {
        return estado == null ? null : estado.getName();
    }

    private static boolean terminada(UserTaskState estado) {
        return estado != null && estado.isTerminate();
    }

    private static Map<String, Object> entradas(Map<String, Object> entradas) {
        Map<String, Object> resultado = new LinkedHashMap<>();
        if (entradas != null) {
            entradas.forEach((k, v) -> {
                if (!ENTRADAS_TECNICAS.contains(k)) {
                    resultado.put(k, v);
                }
            });
        }
        return resultado;
    }

    private static Map<String, Object> copia(Map<String, Object> mapa) {
        return mapa == null ? Map.of() : new LinkedHashMap<>(mapa);
    }

    private static Set<String> copia(Set<String> conjunto) {
        return conjunto == null ? Set.of() : new LinkedHashSet<>(conjunto);
    }
}
