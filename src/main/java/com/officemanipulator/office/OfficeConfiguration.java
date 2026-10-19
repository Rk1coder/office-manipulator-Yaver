package com.officemanipulator.office;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name="app.office.enabled",havingValue="true")
public class OfficeConfiguration {
    @Bean OfficeTokens officeTokens(ObjectMapper json,@Value("${app.office.jwt-secret}") String secret) {return new OfficeTokens(json,secret);}
    @Bean OfficeDocumentClient officeDocumentClient(ObjectMapper json,OfficeTokens tokens,
        @Value("${app.office.internal-url}") String internal,@Value("${app.office.public-url}") String external) {
        return new OfficeDocumentClient(json,tokens,internal,external);
    }
}
