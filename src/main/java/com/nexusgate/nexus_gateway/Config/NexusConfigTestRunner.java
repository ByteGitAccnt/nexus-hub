/*

package com.nexusgate.nexus_gateway.Config;

import com.nexusgate.nexus_gateway.Config.Logging.LoggingFields;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;


@Component
public class NexusConfigTestRunner implements CommandLineRunner {

    private final NexusConfigLoader configLoader;

    public NexusConfigTestRunner(NexusConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @Override
    public void run(String... args) {

        NexusConfig config = configLoader.load();
        System.out.println("Logging enabled:" + config.getLogging().isEnabled());
        LoggingFields feilds = config.getLogging().getFields();
            System.out.println(
                    "\nDuration" + feilds.isDuration() +
                            "\npath" + feilds.isPath() +
                            "\nmethod" + feilds.isMethod() +
                            "\nservice" + feilds.isService() +
                            "\nstatus" + feilds.isStatus() +
                            "\nrequestId" + feilds.isRequestId() +
                            "\nclientIp" + feilds.isClientIp() +
                            "\ntimestamp" + feilds.isTimestamp()


            );

    }
}

*/
