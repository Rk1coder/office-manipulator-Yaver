package com.officemanipulator.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Optional bridge-bound listener for ONLYOFFICE's authenticated file transport.
 * WebConfig rejects every ordinary application route on this connector, regardless of Host.
 */
@Configuration
@ConditionalOnProperty(name="app.office.transport.enabled",havingValue="true")
public class OfficeTransportConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> officeTransport(
            @Value("${app.office.transport.address}") String address,
            @Value("${app.office.transport.port}") int port,
            @Value("${server.port:8080}") int publicPort,
            @Value("${app.office.enabled:false}") boolean enabled) {
        if(!enabled) throw new IllegalArgumentException("Office transport requires ONLYOFFICE integration");
        Connector connector=connector(address,port,publicPort);
        return server -> server.addAdditionalTomcatConnectors(connector);
    }
    static Connector connector(String address,int port,int publicPort) {
        if(address==null || !address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}") || address.equals("0.0.0.0")
                || port<1024 || port>65535 || port==publicPort)
            throw new IllegalArgumentException("Use a concrete Docker bridge IPv4 address and a separate unprivileged port");
        try {
            var ip=java.net.InetAddress.getByName(address);
            if(!ip.isSiteLocalAddress() && !ip.isLoopbackAddress()) throw new IllegalArgumentException("Office transport requires a private address");
        } catch(java.net.UnknownHostException e){throw new IllegalArgumentException("Invalid transport address",e);}
        Connector connector=new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(port);connector.setProperty("address",address);
        connector.setProperty("connectionTimeout","20000");
        connector.setProperty("maxThreads","8");connector.setProperty("maxConnections","32");connector.setProperty("acceptCount","16");
        connector.setMaxPostSize(262144);
        return connector;
    }
}
