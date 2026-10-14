package com.nexusgate.nexus_gateway.Config.Logging;

import lombok.Data;

@Data
public class LoggingConfig {

    private boolean enabled = false;
    private LoggingFields fields = new LoggingFields();
}
