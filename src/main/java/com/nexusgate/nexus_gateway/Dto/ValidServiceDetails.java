package com.nexusgate.nexus_gateway.Dto;

import java.util.UUID;

public record ValidServiceDetails(
        UUID serviceId,
        String serviceName,
        String serviceTargetUrl,
        String serviceBasePath,
        String serviceTargetPath
) {
}
