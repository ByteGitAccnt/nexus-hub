package com.nexusgate.nexus_gateway;

import com.nexusgate.nexus_gateway.Config.NexusConfig;
import com.nexusgate.nexus_gateway.Config.NexusRouteBuilder;
import com.nexusgate.nexus_gateway.Config.ServiceConfig;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitConfig;
import com.nexusgate.nexus_gateway.Config.Security.SecurityConfig;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
class NexusGatewayApplicationTests {

	@Test
	void contextLoads() {
	}

	@Test
	void nexusHubRouteStripsOnePrefixSegmentBeforeForwarding() {
		NexusConfig config = new NexusConfig();
		SecurityConfig security = new SecurityConfig();
		security.setEnabled(false);
		config.setSecurity(security);
		config.setRateLimit(new RateLimitConfig());

		ServiceConfig nexusHub = new ServiceConfig();
		nexusHub.setUrl("http://localhost:8090");
		nexusHub.setPath("/nexushub/**");
		config.setServices(Map.of("nexus-hub", nexusHub));

		RouteDefinition route = new NexusRouteBuilder(config)
				.getRouteDefinitions()
				.collectList()
				.block()
				.getFirst();

		assertEquals(URI.create("http://localhost:8090"), route.getUri());
		assertEquals("Path", route.getPredicates().getFirst().getName());
		assertEquals("/nexushub/**", route.getPredicates().getFirst().getArgs().get("pattern"));
		List<FilterDefinition> filters = route.getFilters();
		assertEquals(1, filters.size());
		assertEquals("StripPrefix", filters.getFirst().getName());
		assertEquals("1", filters.getFirst().getArgs().get("parts"));
		assertFalse(filters.stream().anyMatch(filter -> filter.getName().equals("RewritePath")));
	}
}
