package com.officemanipulator.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class OfficeTransportTest {
    private WebConfig filter() {
        WebConfig filter=new WebConfig();
        ReflectionTestUtils.setField(filter,"officeEnabled",true);
        ReflectionTestUtils.setField(filter,"officeTransportPort",8091);
        ReflectionTestUtils.setField(filter,"appInternalUrl","http://172.22.0.1:8091");
        return filter;
    }
    @Test void transportPortRejectsOrdinaryApiEvenWithForgedLocalhostHost() throws Exception {
        for(String path:new String[]{"/api/budget","/api/chat","/index.html","/office-plugin/config.json","/api/office/import"}) {
            var request=new MockHttpServletRequest("GET",path);request.setLocalPort(8091);request.setServerName("localhost");
            var response=new MockHttpServletResponse();var reached=new AtomicBoolean();
            filter().doFilter(request,response,(req,res)->reached.set(true));
            assertEquals(403,response.getStatus(),path);assertFalse(reached.get());
        }
    }
    @Test void transportAllowsOnlyCorrectMethodAndExactSignedRouteToReachController() throws Exception {
        String prefix="/api/office/sessions/12345678-1234-1234-1234-123456789abc/";
        for(String[] route:new String[][]{{"GET","document"},{"POST","callback"}}) {
            var request=new MockHttpServletRequest(route[0],prefix+route[1]);request.setLocalPort(8091);request.setServerName("172.22.0.1");
            var reached=new AtomicBoolean();filter().doFilter(request,new MockHttpServletResponse(),(req,res)->reached.set(true));assertTrue(reached.get());
        }
        var wrongMethod=new MockHttpServletRequest("POST",prefix+"document");wrongMethod.setLocalPort(8091);wrongMethod.setServerName("localhost");
        var response=new MockHttpServletResponse();filter().doFilter(wrongMethod,response,(req,res)->fail("Wrong method reached controller"));assertEquals(403,response.getStatus());
    }
    @Test void conversionSourceAllowsOnlyGetToTicketCheckingController() throws Exception {
        String path="/api/office/convert-source/12345678-1234-1234-1234-123456789abc";
        var request=new MockHttpServletRequest("GET",path);request.setLocalPort(8091);request.setServerName("172.22.0.1");
        var reached=new AtomicBoolean();filter().doFilter(request,new MockHttpServletResponse(),(req,res)->reached.set(true));
        assertTrue(reached.get());
        request.setMethod("POST");var response=new MockHttpServletResponse();
        filter().doFilter(request,response,(req,res)->fail("Only GET is allowed"));assertEquals(403,response.getStatus());
    }
    @Test void browserPortRetainsNormalLocalApiAccess() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/budget");request.setLocalPort(8090);request.setServerName("localhost");
        var reached=new AtomicBoolean();filter().doFilter(request,new MockHttpServletResponse(),(req,res)->reached.set(true));assertTrue(reached.get());
    }
    @Test void extraConnectorRejectsWildcardBindingAndSamePublicPort() {
        assertThrows(IllegalArgumentException.class,()->OfficeTransportConfiguration.connector("0.0.0.0",8091,8090));
        assertThrows(IllegalArgumentException.class,()->OfficeTransportConfiguration.connector("172.22.0.1",8090,8090));
        var connector=OfficeTransportConfiguration.connector("172.22.0.1",8091,8090);
        assertEquals(8091,connector.getPort());assertEquals("172.22.0.1",((java.net.InetAddress)connector.getProperty("address")).getHostAddress());
    }
}
