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
 * This file is part of SOAR4UEBA - A SOAR solution based on BPM paradigm.
 */
package us.dit.ueba.soar.services.openc2;

import org.oasis.openc2.lycan.OpenC2Message;
import org.oasis.openc2.lycan.targets.DomainName;
import org.oasis.openc2.lycan.targets.Target;
import org.oasis.openc2.lycan.types.ActionType;

/**
 * Ejemplo simple de productor y consumidor OpenC2
 */
public class SimpleOpenC2Example {

    public static void main(String[] args) throws Exception {

        // 1. Iniciar el servidor (consumidor)
        OpenC2Consumer server = new OpenC2Consumer(8080);
        server.start();

        // Esperar a que el servidor esté listo
        Thread.sleep(1000);

        // 2. Crear un cliente (productor)
        OpenC2Producer client = new OpenC2Producer("http://localhost:8080");

        // ========== COMANDO 1: QUERY ==========
        System.out.println("\n" + "=".repeat(60));
        System.out.println("COMANDO 1: QUERY (Consultar un dominio)");
        System.out.println("=".repeat(60));

        Target target1 = createTarget(new DomainName(), "example.com");
        OpenC2Message command1 = new OpenC2Message(ActionType.QUERY, target1);
        command1.setCommandId("cmd-001-query");
        System.out.println("📨 Cliente enviando: QUERY sobre example.com");
        client.sendCommand(command1);
        Thread.sleep(1000);

        // ========== COMANDO 2: DENY (BLOQUEAR) ==========
        System.out.println("\n" + "=".repeat(60));
        System.out.println("COMANDO 2: DENY (Bloquear un dominio malicioso)");
        System.out.println("=".repeat(60));

        Target target2 = createTarget(new DomainName(), "malicious.com");
        OpenC2Message command2 = new OpenC2Message(ActionType.DENY, target2);
        command2.setCommandId("cmd-002-deny");
        System.out.println("📨 Cliente enviando: DENY sobre malicious.com");
        client.sendCommand(command2);
        Thread.sleep(1000);

        // ========== COMANDO 3: ALLOW (PERMITIR) ==========
        System.out.println("\n" + "=".repeat(60));
        System.out.println("COMANDO 3: ALLOW (Permitir una IP confiable)");
        System.out.println("=".repeat(60));

        Target target3 = createTarget(new DomainName(), "trusted.com");
        OpenC2Message command3 = new OpenC2Message(ActionType.ALLOW, target3);
        command3.setCommandId("cmd-003-allow");
        System.out.println("📨 Cliente enviando: ALLOW sobre trusted.com");
        client.sendCommand(command3);
        Thread.sleep(1000);

        // Detener el servidor
        System.out.println("\n" + "=".repeat(60));
        server.stop();
    }

    private static Target createTarget(DomainName domainName, String domain) {
        domainName.setDomainName(domain);
        Target target = new Target();
        target.setDomainName(domainName);
        return target;
    }
}
