package us.dit.ueba.soar.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementación de la tarea de servicio "Ajustar logs" del proceso gestion-incidente.
 * Por ahora solo registra la incidencia; aquí irá la lógica real (p.ej. comando OpenC2).
 */
@Component
public class LogsService {

    private static final Logger LOG = LoggerFactory.getLogger(LogsService.class);

    public void ajustarLogs(String incidencia) {
        LOG.info("Ajustando logs para la incidencia: {}", incidencia);
    }
}
