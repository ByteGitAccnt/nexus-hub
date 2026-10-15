package com.nexusgate.management.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.nexusgate.management.model.ServiceAuthMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.net.URISyntaxException;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateServiceRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @NotBlank
        @Size(max = 2048)
        String targetUrl,

        @NotBlank
        @Size(max = 255)
        @Pattern(regexp = "^/(?!/)[^?#]*$")
        String basePath,

        @Size(max = 2048)
        String targetPath,

        @NotNull
        ServiceAuthMode authMode
) {
    @AssertTrue(message = "must be an absolute HTTP(S) URL with a host and without user-info or fragment")
    public boolean isTargetUrlValid() {
        if (targetUrl == null || targetUrl.isBlank()) {
            return true;
        }
        try {
            URI uri = new URI(targetUrl);
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    @AssertTrue(message = "must be a single absolute path prefix without query or fragment")
    public boolean isTargetPathValid() {
        return targetPath == null
                || targetPath.isBlank()
                || (targetPath.startsWith("/")
                && !targetPath.startsWith("//")
                && targetPath.indexOf('?') < 0
                && targetPath.indexOf('#') < 0);
    }
}
