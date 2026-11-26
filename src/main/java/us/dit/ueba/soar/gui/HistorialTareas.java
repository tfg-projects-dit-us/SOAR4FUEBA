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
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.kie.kogito.auth.IdentityProvider;
import org.kie.kogito.usertask.UserTaskEventListener;
import org.kie.kogito.usertask.events.UserTaskStateEvent;
import org.kie.kogito.usertask.lifecycle.UserTaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Histórico de las tareas humanas terminadas.
 * <p>
 * Kogito borra una tarea de su almacén en cuanto termina (completada, omitida, obsoleta...), así que la API de
 * tareas solo devuelve las pendientes. Este {@link UserTaskEventListener} guarda una foto de cada tarea en el
 * momento en que termina para que la consola SOC pueda mostrar las ya cerradas. Kogito lo registra solo por ser un
 * bean de Spring.
 * <p>
 * <b>Provisional:</b> el histórico está en memoria, se pierde al reiniciar y guarda como mucho
 * {@code soar.gui.historial.maximo} tareas (las más antiguas se descartan). Para conservarlo habría que
 * persistirlo (base de datos) o usar el Data Index de Kogito.
 */
@Component
public class HistorialTareas implements UserTaskEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(HistorialTareas.class);

    private final int maximo;

    /** Tareas terminadas, la más reciente primero */
    private final Deque<TareaSoc> tareas = new ArrayDeque<>();

    public HistorialTareas(@Value("${soar.gui.historial.maximo:1000}") int maximo) {
        this.maximo = maximo;
    }

    @Override
    public void onUserTaskState(UserTaskStateEvent evento) {
        UserTaskState estado = evento.getNewStatus();
        if (estado == null || !estado.isTerminate()) {
            return;
        }
        Instant fecha = evento.getEventDate() == null ? Instant.now() : evento.getEventDate().toInstant();
        TareaSoc tarea = TareaSoc.terminada(evento.getUserTaskInstance(), estado, fecha, evento.getEventUser());
        LOG.debug("Tarea {} ({}) terminada con estado {}", tarea.id(), tarea.nombre(), tarea.estado());
        synchronized (tareas) {
            tareas.removeIf(t -> t.id().equals(tarea.id()));
            tareas.addFirst(tarea);
            while (tareas.size() > maximo) {
                tareas.removeLast();
            }
        }
    }

    /** Tareas terminadas que puede ver el usuario, la más reciente primero. */
    public List<TareaSoc> visiblesPara(IdentityProvider usuario) {
        synchronized (tareas) {
            return tareas.stream().filter(t -> visible(t, usuario)).toList();
        }
    }

    /** Tarea terminada con ese id, si existe y el usuario puede verla. */
    public Optional<TareaSoc> buscar(String id, IdentityProvider usuario) {
        synchronized (tareas) {
            return tareas.stream().filter(t -> t.id().equals(id)).filter(t -> visible(t, usuario)).findFirst();
        }
    }

    /**
     * Mismo criterio que Kogito para las tareas pendientes: la ve quien la tuvo asignada o la cerró, sus usuarios
     * potenciales y los miembros de sus grupos potenciales.
     */
    static boolean visible(TareaSoc tarea, IdentityProvider usuario) {
        String nombre = usuario.getName();
        if (Objects.equals(nombre, tarea.propietario()) || Objects.equals(nombre, tarea.cerradaPor())
                || tarea.usuarios().contains(nombre)) {
            return true;
        }
        Collection<String> grupos = usuario.getRoles();
        return grupos != null && grupos.stream().anyMatch(tarea.grupos()::contains);
    }
}
