/*
 * Copyright 2026 Universidad de Sevilla/Departamento de Ingeniería Telemática
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 * This file is part of SOAR4FUEBA - A SOAR solution based on BPM paradigm.
 *
 * Consola SOC: lista las tareas humanas pendientes y terminadas del usuario y permite consultarlas,
 * reclamarlas, liberarlas, guardar su resultado y completarlas. API: /soc/api (TareasSocController).
 *
 * Autenticación: HTTP Basic en cada petición (credenciales en sessionStorage, se borran al cerrar la pestaña).
 * La cabecera X-Requested-With hace que Spring Security responda 401 sin WWW-Authenticate, así el navegador
 * no muestra su propio diálogo de credenciales.
 *
 * Todo lo que llega del servidor se pinta con textContent: los datos de las incidencias vienen de fuera
 * (Kafka) y no son de confianza.
 */
(() => {
    'use strict';

    const API = 'api';
    const REFRESCO_MS = 15000;
    const CLAVE_SESION = 'soar4fueba.soc.credenciales';

    /** Nombres legibles de las transiciones y estados de Kogito */
    const TRANSICIONES = {
        claim: 'Reclamar',
        release: 'Liberar',
        complete: 'Completar y cerrar',
        skip: 'Omitir',
    };
    const ESTADOS = {
        Ready: 'Pendiente',
        Active: 'Pendiente',
        Reserved: 'Reclamada',
        Completed: 'Completada',
        Skipped: 'Omitida',
        Obsolete: 'Obsoleta',
        Error: 'Error',
    };

    const estado = {
        credenciales: null,
        usuario: null,
        pestana: 'pendientes',
        tareas: { pendientes: [], terminadas: [] },
        seleccionada: null,
        temporizador: null,
    };

    const $ = (id) => document.getElementById(id);

    /** Crea un elemento. Los hijos de tipo texto se insertan como nodos de texto (nunca como HTML). */
    function el(etiqueta, atributos = {}, ...hijos) {
        const nodo = document.createElement(etiqueta);
        for (const [clave, valor] of Object.entries(atributos)) {
            if (valor === null || valor === undefined || valor === false) continue;
            if (clave === 'class') nodo.className = valor;
            else if (clave.startsWith('on')) nodo.addEventListener(clave.slice(2), valor);
            else nodo.setAttribute(clave, valor === true ? '' : valor);
        }
        for (const hijo of hijos.flat()) {
            if (hijo === null || hijo === undefined || hijo === false) continue;
            nodo.append(hijo instanceof Node ? hijo : document.createTextNode(String(hijo)));
        }
        return nodo;
    }

    // ---------------------------------------------------------------- API

    function base64(texto) {
        const bytes = new TextEncoder().encode(texto);
        let binario = '';
        bytes.forEach((b) => { binario += String.fromCharCode(b); });
        return btoa(binario);
    }

    class ErrorApi extends Error {
        constructor(status, mensaje) {
            super(mensaje);
            this.status = status;
        }
    }

    async function api(ruta, { metodo = 'GET', cuerpo, credenciales = estado.credenciales } = {}) {
        const cabeceras = {
            Accept: 'application/json',
            'X-Requested-With': 'XMLHttpRequest',
            Authorization: 'Basic ' + credenciales,
        };
        if (cuerpo !== undefined) cabeceras['Content-Type'] = 'application/json';
        const respuesta = await fetch(`${API}${ruta}`, {
            method: metodo,
            headers: cabeceras,
            body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
            credentials: 'omit',
            cache: 'no-store',
        });
        if (respuesta.status === 401 && credenciales === estado.credenciales && estado.usuario) {
            cerrarSesion('La sesión ha caducado o las credenciales ya no son válidas.');
        }
        if (!respuesta.ok) {
            let mensaje = `Error ${respuesta.status}`;
            try {
                const error = await respuesta.json();
                mensaje = error.detail || error.message || error.error || mensaje;
            } catch (e) { /* sin cuerpo JSON */ }
            throw new ErrorApi(respuesta.status, mensaje);
        }
        return respuesta.status === 204 ? null : respuesta.json();
    }

    // ---------------------------------------------------------------- Sesión

    async function iniciarSesion(usuario, clave) {
        const credenciales = base64(`${usuario}:${clave}`);
        const yo = await api('/yo', { credenciales });
        estado.credenciales = credenciales;
        estado.usuario = yo;
        try { sessionStorage.setItem(CLAVE_SESION, credenciales); } catch (e) { /* almacenamiento no disponible */ }
        mostrarConsola();
    }

    function cerrarSesion(motivo) {
        clearInterval(estado.temporizador);
        estado.credenciales = null;
        estado.usuario = null;
        estado.seleccionada = null;
        try { sessionStorage.removeItem(CLAVE_SESION); } catch (e) { /* almacenamiento no disponible */ }
        $('vista-consola').hidden = true;
        $('sesion').hidden = true;
        $('vista-acceso').hidden = false;
        $('acceso-clave').value = '';
        const error = $('acceso-error');
        error.hidden = !motivo;
        error.textContent = motivo || '';
        $('acceso-usuario').focus();
    }

    async function restaurarSesion() {
        let guardadas = null;
        try { guardadas = sessionStorage.getItem(CLAVE_SESION); } catch (e) { /* almacenamiento no disponible */ }
        if (!guardadas) return;
        try {
            estado.usuario = await api('/yo', { credenciales: guardadas });
            estado.credenciales = guardadas;
            mostrarConsola();
        } catch (e) {
            try { sessionStorage.removeItem(CLAVE_SESION); } catch (e2) { /* nada */ }
        }
    }

    function mostrarConsola() {
        $('vista-acceso').hidden = true;
        $('vista-consola').hidden = false;
        $('sesion').hidden = false;
        $('usuario').textContent = estado.usuario.usuario;
        $('grupos').textContent = estado.usuario.grupos.length
            ? 'Grupos: ' + estado.usuario.grupos.join(', ') : '';
        cargarTareas();
        clearInterval(estado.temporizador);
        estado.temporizador = setInterval(() => cargarTareas({ silencioso: true }), REFRESCO_MS);
    }

    // ---------------------------------------------------------------- Lista

    async function cargarTareas({ silencioso = false } = {}) {
        try {
            const [pendientes, terminadas] = await Promise.all([
                api('/tareas?estado=pendientes'),
                api('/tareas?estado=terminadas'),
            ]);
            estado.tareas = { pendientes, terminadas };
            pintarLista();
        } catch (e) {
            if (!silencioso && estado.usuario) avisar(e.message, true);
        }
    }

    function filtrar(tareas) {
        const filtro = $('filtro').value;
        const texto = $('buscar').value.trim().toLowerCase();
        const yo = estado.usuario.usuario;
        return tareas.filter((t) => {
            if (filtro === 'libres' && t.propietario) return false;
            if (filtro === 'mias' && t.propietario !== yo && t.cerradaPor !== yo) return false;
            if (!texto) return true;
            return JSON.stringify([t.id, t.nombre, t.descripcion, t.entradas, t.propietario])
                .toLowerCase().includes(texto);
        });
    }

    function pintarLista() {
        $('n-pendientes').textContent = estado.tareas.pendientes.length;
        $('n-terminadas').textContent = estado.tareas.terminadas.length;
        const tareas = filtrar(estado.tareas[estado.pestana]);
        const lista = $('tareas');
        lista.replaceChildren(...tareas.map(elementoTarea));
        $('lista-vacia').hidden = tareas.length > 0;
    }

    function elementoTarea(t) {
        const pie = t.terminada
            ? [`Cerrada por ${t.cerradaPor || t.propietario || '—'}`, fecha(t.fechaFin)]
            : [t.propietario ? `Reclamada por ${t.propietario}` : 'Sin asignar', `Grupos: ${t.grupos.join(', ') || '—'}`];
        return el('li', {
            class: 'tarea' + (t.id === estado.seleccionada ? ' seleccionada' : ''),
            tabindex: 0,
            'data-id': t.id,
            onclick: () => seleccionar(t.id),
            onkeydown: (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); seleccionar(t.id); } },
        },
        el('div', { class: 'tarea-cabecera' },
            el('span', { class: 'tarea-nombre' }, t.descripcion || t.nombre || 'Tarea'),
            etiquetaEstado(t.estado)),
        el('div', { class: 'tarea-resumen' }, resumen(t)),
        el('div', { class: 'tarea-pie' }, pie.map((p) => el('span', {}, p))));
    }

    /** Texto breve para la lista: la incidencia si la hay, si no la primera entrada. */
    function resumen(t) {
        const entradas = t.entradas || {};
        const valor = entradas.incidencia ?? Object.values(entradas)[0];
        if (valor === undefined || valor === null) return `Proceso ${t.procesoId || '—'}`;
        return typeof valor === 'string' ? valor : JSON.stringify(valor);
    }

    function etiquetaEstado(nombre) {
        const clase = ['Ready', 'Active', 'Reserved', 'Completed'].includes(nombre) ? nombre : 'otro';
        return el('span', { class: `estado estado-${clase}` }, ESTADOS[nombre] || nombre || '—');
    }

    // ---------------------------------------------------------------- Detalle

    async function seleccionar(id) {
        estado.seleccionada = id;
        document.querySelectorAll('.tarea').forEach((li) => li.classList.toggle('seleccionada', li.dataset.id === id));
        try {
            pintarDetalle(await api(`/tareas/${encodeURIComponent(id)}`));
        } catch (e) {
            avisar(e.message, true);
            if (e.status === 404) {
                estado.seleccionada = null;
                detalleVacio();
                cargarTareas({ silencioso: true });
            }
        }
    }

    function detalleVacio() {
        $('detalle').replaceChildren(el('div', { class: 'vacio detalle-vacio' },
            el('p', {}, 'Selecciona una tarea para consultarla.')));
    }

    function pintarDetalle(d) {
        const t = d.tarea;
        const yo = estado.usuario.usuario;
        const esMia = t.propietario === yo;
        const editable = !t.terminada && esMia;
        const { incidencia, ...otrasEntradas } = t.entradas || {};

        const contenido = [
            el('div', { class: 'detalle-cabecera' },
                el('div', {},
                    el('h2', {}, t.descripcion || t.nombre || 'Tarea'),
                    el('div', { class: 'tenue' }, `Tarea ${t.id}`)),
                etiquetaEstado(t.estado)),
        ];

        if (incidencia !== undefined && incidencia !== null) {
            contenido.push(el('div', { class: 'bloque' },
                el('h3', {}, 'Incidencia'),
                el('div', { class: 'incidencia' }, formatear(incidencia))));
        }

        contenido.push(el('div', { class: 'bloque' },
            el('h3', {}, 'Datos de la tarea'),
            listaDatos({
                'Tarea': t.nombre,
                'Proceso': t.procesoId,
                'Instancia del proceso': t.instanciaProcesoId,
                'Grupos': t.grupos.join(', ') || '—',
                'Usuarios': t.usuarios.join(', ') || null,
                'Reclamada por': t.propietario || 'Nadie',
                'Prioridad': t.prioridad,
                'Cerrada por': t.cerradaPor,
                'Fecha de cierre': t.fechaFin ? fecha(t.fechaFin) : null,
                ...otrasEntradas,
            })));

        if (d.procesoActivo && Object.keys(d.datosProceso).length) {
            contenido.push(el('div', { class: 'bloque' },
                el('h3', {}, 'Contexto del proceso'),
                listaDatos(d.datosProceso)));
        } else if (!t.terminada) {
            contenido.push(el('p', { class: 'nota bloque' }, 'La instancia del proceso ya no está activa.'));
        }

        contenido.push(bloqueResultado(d, editable));
        $('detalle').replaceChildren(...contenido);
    }

    function bloqueResultado(d, editable) {
        const t = d.tarea;
        const bloque = el('div', { class: 'bloque' }, el('h3', {}, 'Resultado'));

        if (t.terminada) {
            bloque.append(Object.keys(t.salidas).length
                ? listaDatos(t.salidas)
                : el('p', { class: 'nota' }, 'La tarea se cerró sin datos de salida.'));
            return bloque;
        }

        if (!editable) {
            bloque.append(el('p', { class: 'nota' }, t.propietario
                ? `La tarea la tiene ${t.propietario}. Solo su propietario puede ejecutarla.`
                : 'Reclama la tarea para poder ejecutarla y cerrarla.'));
        }

        const formulario = el('form', { class: 'formulario', onsubmit: (ev) => ev.preventDefault() });
        d.formulario.forEach((campo) => formulario.append(campoFormulario(campo, t.salidas[campo.nombre], editable)));
        if (!d.formulario.length) {
            formulario.append(el('p', { class: 'nota' }, 'Esta tarea no tiene datos de salida.'));
        }
        bloque.append(formulario);

        const acciones = el('div', { class: 'acciones' });
        const puede = (tr) => d.transiciones.includes(tr);
        if (puede('claim')) acciones.append(botonTransicion(t, 'claim', 'boton-primario'));
        if (editable && d.formulario.length) {
            acciones.append(el('button', {
                class: 'boton boton-secundario', type: 'button',
                onclick: () => guardar(t, formulario, d.formulario),
            }, 'Guardar borrador'));
        }
        if (puede('complete') && editable) {
            acciones.append(el('button', {
                class: 'boton boton-primario', type: 'button',
                onclick: () => completar(t, formulario, d.formulario),
            }, TRANSICIONES.complete));
        }
        if (puede('release')) acciones.append(botonTransicion(t, 'release', 'boton-secundario'));
        if (puede('skip')) acciones.append(botonTransicion(t, 'skip', 'boton-secundario boton-peligro'));
        if (acciones.childElementCount) bloque.append(acciones);
        return bloque;
    }

    function campoFormulario(campo, valor, editable) {
        const id = `campo-${campo.nombre}`;
        let control;
        switch (campo.tipo) {
            case 'boolean':
                control = el('select', { id, name: campo.nombre, disabled: !editable },
                    el('option', { value: '' }, '— Sin indicar —'),
                    el('option', { value: 'true', selected: valor === true }, 'Sí'),
                    el('option', { value: 'false', selected: valor === false }, 'No'));
                break;
            case 'integer':
            case 'number':
                control = el('input', {
                    id, name: campo.nombre, type: 'number', disabled: !editable,
                    step: campo.tipo === 'integer' ? '1' : 'any', value: valor ?? '',
                });
                break;
            case 'string':
                control = el('textarea', { id, name: campo.nombre, disabled: !editable });
                control.value = valor ?? '';
                break;
            default:
                control = el('textarea', { id, name: campo.nombre, disabled: !editable, placeholder: 'JSON' });
                control.value = valor === undefined || valor === null ? '' : JSON.stringify(valor, null, 2);
        }
        control.dataset.tipo = campo.tipo;
        return el('label', { for: id }, etiquetaCampo(campo.nombre), control);
    }

    /** Lee los valores del formulario con el tipo que indica el esquema de la tarea. */
    function leerFormulario(formulario, campos) {
        const datos = {};
        for (const campo of campos) {
            const control = formulario.elements[campo.nombre];
            const texto = control.value.trim();
            if (texto === '') continue;
            switch (campo.tipo) {
                case 'boolean': datos[campo.nombre] = texto === 'true'; break;
                case 'integer':
                case 'number': datos[campo.nombre] = Number(texto); break;
                case 'string': datos[campo.nombre] = control.value; break;
                default:
                    try {
                        datos[campo.nombre] = JSON.parse(texto);
                    } catch (e) {
                        throw new Error(`El campo «${etiquetaCampo(campo.nombre)}» no es un JSON válido.`);
                    }
            }
        }
        return datos;
    }

    function botonTransicion(t, transicion, clase) {
        return el('button', {
            class: `boton ${clase}`, type: 'button',
            onclick: (ev) => ejecutar(ev.currentTarget, t, transicion),
        }, TRANSICIONES[transicion]);
    }

    async function ejecutar(boton, t, transicion, datos = {}) {
        const botones = $('detalle').querySelectorAll('button');
        botones.forEach((b) => { b.disabled = true; });
        try {
            const d = await api(`/tareas/${encodeURIComponent(t.id)}/${transicion}`, { metodo: 'POST', cuerpo: datos });
            avisar({
                claim: 'Tarea reclamada.',
                release: 'Tarea liberada.',
                complete: 'Tarea completada y cerrada.',
                skip: 'Tarea omitida.',
            }[transicion]);
            pintarDetalle(d);
            if (d.tarea.terminada) cambiarPestana('terminadas');
            await cargarTareas({ silencioso: true });
        } catch (e) {
            avisar(e.message, true);
            botones.forEach((b) => { b.disabled = false; });
        }
    }

    async function guardar(t, formulario, campos) {
        try {
            const d = await api(`/tareas/${encodeURIComponent(t.id)}/salidas`,
                { metodo: 'PUT', cuerpo: leerFormulario(formulario, campos) });
            avisar('Borrador guardado.');
            pintarDetalle(d);
        } catch (e) {
            avisar(e.message, true);
        }
    }

    function completar(t, formulario, campos) {
        let datos;
        try {
            datos = leerFormulario(formulario, campos);
        } catch (e) {
            avisar(e.message, true);
            return;
        }
        const faltan = campos.filter((c) => !(c.nombre in datos)).map((c) => etiquetaCampo(c.nombre));
        const aviso = faltan.length ? `\n\nSin rellenar: ${faltan.join(', ')}.` : '';
        if (!confirm(`¿Completar y cerrar la tarea «${t.descripcion || t.nombre}»?${aviso}`)) return;
        ejecutar(null, t, 'complete', datos);
    }

    // ---------------------------------------------------------------- Utilidades

    function listaDatos(objeto) {
        const dl = el('dl', { class: 'datos' });
        for (const [clave, valor] of Object.entries(objeto)) {
            if (valor === null || valor === undefined || valor === '') continue;
            dl.append(el('dt', {}, etiquetaCampo(clave)), el('dd', {}, valorDato(valor)));
        }
        return dl.childElementCount ? dl : el('p', { class: 'nota' }, 'Sin datos.');
    }

    function valorDato(valor) {
        if (typeof valor === 'boolean') return valor ? 'Sí' : 'No';
        if (typeof valor === 'object') return el('pre', { class: 'valor-json' }, JSON.stringify(valor, null, 2));
        return el('span', { class: 'valor-largo' }, String(valor));
    }

    function formatear(valor) {
        return typeof valor === 'string' ? valor : JSON.stringify(valor, null, 2);
    }

    /** "resuelta" -> "Resuelta", "instanciaProceso" -> "Instancia proceso" */
    function etiquetaCampo(nombre) {
        const texto = String(nombre).replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ');
        return texto.charAt(0).toUpperCase() + texto.slice(1);
    }

    function fecha(iso) {
        if (!iso) return '—';
        const f = new Date(iso);
        return Number.isNaN(f.getTime()) ? String(iso) : f.toLocaleString('es-ES');
    }

    function avisar(mensaje, esError = false) {
        if (!mensaje) return;
        const aviso = el('div', { class: 'aviso' + (esError ? ' aviso-error' : ''), role: esError ? 'alert' : 'status' }, mensaje);
        $('avisos').append(aviso);
        setTimeout(() => aviso.remove(), esError ? 7000 : 3500);
    }

    function cambiarPestana(nombre) {
        estado.pestana = nombre;
        document.querySelectorAll('.pestana').forEach((p) => {
            const activa = p.dataset.estado === nombre;
            p.classList.toggle('activa', activa);
            p.setAttribute('aria-selected', String(activa));
        });
        pintarLista();
    }

    // ---------------------------------------------------------------- Arranque

    document.addEventListener('DOMContentLoaded', () => {
        $('form-acceso').addEventListener('submit', async (ev) => {
            ev.preventDefault();
            $('acceso-error').hidden = true;
            try {
                await iniciarSesion($('acceso-usuario').value.trim(), $('acceso-clave').value);
            } catch (e) {
                const error = $('acceso-error');
                error.textContent = e.status === 401 ? 'Usuario o contraseña incorrectos.' : e.message;
                error.hidden = false;
            }
        });
        $('salir').addEventListener('click', () => cerrarSesion());
        $('refrescar').addEventListener('click', () => cargarTareas());
        $('filtro').addEventListener('change', pintarLista);
        $('buscar').addEventListener('input', pintarLista);
        document.querySelectorAll('.pestana').forEach((p) =>
            p.addEventListener('click', () => cambiarPestana(p.dataset.estado)));
        restaurarSesion();
    });
})();
