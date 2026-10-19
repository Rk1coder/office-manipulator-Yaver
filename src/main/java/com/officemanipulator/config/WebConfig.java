package com.officemanipulator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Set;

/** This application is a personal, local tool. No remote or cross-origin API access. */
@Component
public class WebConfig extends OncePerRequestFilter {
    private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    @org.springframework.beans.factory.annotation.Value("${app.office.enabled:false}")
    private boolean officeEnabled;
    @org.springframework.beans.factory.annotation.Value("${app.office.public-url:http://localhost:8082}")
    private String officePublicUrl="http://localhost:8082";
    @org.springframework.beans.factory.annotation.Value("${app.office.app-public-url:http://localhost:8090}")
    private String appPublicUrl="http://localhost:8090";
    @org.springframework.beans.factory.annotation.Value("${app.office.app-internal-url:http://office-app:8080}")
    private String appInternalUrl="http://office-app:8080";
    @org.springframework.beans.factory.annotation.Value("${app.office.transport.port:0}")
    private int officeTransportPort;
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path=request.getRequestURI();
        // Container access is allowed only for routes that verify a document ticket or callback JWT.
        boolean conversionSource=path.matches("/api/office/convert-source/[0-9a-fA-F-]{36}");
        boolean officeTransport=officeEnabled && (path.matches("/api/office/sessions/[0-9a-fA-F-]{36}/(document|callback)")
            || path.matches("/api/office/preview/[0-9a-fA-F-]{36}/document") || conversionSource);
        boolean transportListener=officeTransportPort>0 && request.getLocalPort()==officeTransportPort;
        boolean transportMethod=((path.endsWith("/document") || conversionSource) && "GET".equals(request.getMethod()))
            || (path.endsWith("/callback") && "POST".equals(request.getMethod()));
        // Test the actual connector, not Host: a container must not spoof localhost to reach APIs.
        if(transportListener && (!officeTransport || !transportMethod)) {
            response.sendError(403,"This listener only accepts signed document transport");return;
        }
        boolean internalHost=officeTransport && URI.create(appInternalUrl).getHost().equalsIgnoreCase(request.getServerName());
        if (!HOSTS.contains(request.getServerName().toLowerCase(java.util.Locale.ROOT)) && !internalHost) {
            response.sendError(403, "Only localhost access is supported"); return;
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        boolean plugin=officeEnabled && path.startsWith("/office-plugin/");
        if(plugin) {
            response.setHeader("Content-Security-Policy","frame-ancestors 'self' "+officePublicUrl+" "+appPublicUrl);
            response.setHeader("Cache-Control","no-store");
            // ONLYOFFICE fetches config.json from its iframe; all other APIs retain same-origin rules.
            if(officePublicUrl.equals(request.getHeader("Origin"))) {
                response.setHeader("Access-Control-Allow-Origin",officePublicUrl);
                response.setHeader("Vary","Origin");
                response.setHeader("Access-Control-Allow-Methods","GET, OPTIONS");
            }
            if("OPTIONS".equals(request.getMethod())) {response.setStatus(204);return;}
        } else response.setHeader("X-Frame-Options", path.equals("/office-editor.html")?"SAMEORIGIN":"DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            String origin = request.getHeader("Origin");
            boolean allowed = !"cross-site".equals(request.getHeader("Sec-Fetch-Site"));
            if (origin != null) {
                try {
                    URI uri = URI.create(origin);
                    int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                    allowed &= uri.getHost() != null && uri.getHost().equalsIgnoreCase(request.getServerName())
                        && uri.getScheme().equals(request.getScheme()) && port == request.getServerPort()
                        && uri.getRawUserInfo() == null && (uri.getPath() == null || uri.getPath().isEmpty());
                } catch (RuntimeException e) { allowed = false; }
            }
            if (!allowed) { response.sendError(403, "Cross-origin API access is disabled"); return; }
        }
        chain.doFilter(request, response);
    }
}
