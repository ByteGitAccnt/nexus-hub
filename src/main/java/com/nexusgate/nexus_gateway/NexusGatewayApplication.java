package com.nexusgate.nexus_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NexusGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(NexusGatewayApplication.class, args);
	}

}
// code0/c-users-vazha-code0-plans-nexus-gateway-nexushub-strip-prefi
// gate way now connected to the managment -app

// code0/c-users-vazha-code0-plans-nexus-gateway-auth-decision-and-ap
// api key bearer are not deligated into down stream
// No Bearer + public endpoint: allowed.
// Bearer + public endpoint: valid token allowed; invalid token rejected.
// Bearer + protected JWT route: valid token allowed; missing or invalid token
// rejected.
// No Bearer + protected JWT route: rejected.
// No Bearer + protected API_KEY route + X-API-Key: delegated to the API-key
// filter, which returns 401 pending real validation.
// No Bearer + protected API_KEY route without an API key, or with unsupported
// Basic credentials: rejected.
// PUBLIC service without credentials: allowed; an API-key header alone is
// rejected.