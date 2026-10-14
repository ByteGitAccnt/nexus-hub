package com.nexusgate.nexus_gateway.Config.Logging;

import lombok.Data;

@Data
public class LoggingFields {

    private boolean timestamp = true;
    private boolean method = true;
    private boolean path = true;
    private boolean service = true;
    private boolean status = true;
    private boolean duration = true;
    private boolean requestId = true;
    private boolean clientIp = true;
}