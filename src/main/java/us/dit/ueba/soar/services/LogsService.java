package us.dit.ueba.soar.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import us.dit.ueba.soar.ocsf.DetectionFinding;
import us.dit.ueba.soar.services.ocsf.OcsfMessageValidator;

/**
 * Implementación de la tarea de servicio "Ajustar logs" del proceso gestion-incidente.
 * Por ahora solo registra la incidencia; aquí irá la lógica real (p.ej. comando OpenC2).
 * <p>
 * <b>Inyección por campo, no por constructor:</b> Kogito genera para cada tarea de servicio un handler que, además
 * del constructor que usa Spring (recibe este bean ya inyectado), tiene otro que hace {@code new LogsService()}.
 * Ese segundo constructor no se usa con Spring, pero tiene que compilar, así que esta clase necesita un constructor
 * sin argumentos.
 */
@Component
public class LogsService {

    private static final Logger LOG = LoggerFactory.getLogger(LogsService.class);

    @Autowired
    private OcsfMessageValidator validator;

    public void ajustarLogs(String level, String incidencia) {
        LOG.info("Ajustando logs para el nivel: {}. TODO implementar la llamada al productor de comandos openC2", level);
        // Variable local: el bean es único y lo comparten todas las instancias del proceso
        DetectionFinding detectionFinding = validator.readValid(incidencia, DetectionFinding.class);
        LOG.info("DetectionFinding:\n Confidence: {}\nAction: {}\nActivity Name: {}\nAssignee: {}\nDevice: {}\nActor: {}", detectionFinding.getConfidence(), detectionFinding.getAction(), detectionFinding.getActivityName(), detectionFinding.getAssignee(), detectionFinding.getDevice(), detectionFinding.getActor());
        detectionFinding.getObservables().forEach(observable -> LOG.info("Observable: {} - {} - {}", observable.getType(), observable.getValue(), observable.toString()));
    }
}
