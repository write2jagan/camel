/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.hystrix.processor;

import static org.apache.camel.component.hystrix.processor.HystrixConstants.HYSTRIX_RESPONSE_SHORT_CIRCUITED;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hystrix using timeout with Java DSL
 */
public class HystrixCircutExceptionTest extends CamelTestSupport {
    public static final Integer REQUEST_VOLUME_THRESHOLD = 4;
    private static final Logger LOG = LoggerFactory.getLogger(HystrixCircutExceptionTest.class);
    
    HystrixExceptionRoute route = new HystrixExceptionRoute();
 
    
    @Test
    public void testCurcuitOpen() throws Exception{
        LOG.info("testCurcuitOpen start");
        // failing requests
        route.throwException = true;
        for (int i = 0; i < 2* REQUEST_VOLUME_THRESHOLD; i++) {
            try {
                template.asyncRequestBody("direct:start", "Request Body");
            } catch (CamelExecutionException e) {
                LOG.info(e.toString());
            }
        }
        Thread.sleep(1500);
        route.throwException = false;
        try {
            template.requestBody("direct:start", "Request Body");
            LOG.info("Instead curcuit open expected");
        } catch (CamelExecutionException e) {
            LOG.info("Curcuit open expected ", e);
        }
        getMockEndpoint("mock:result").expectedPropertyReceived(HystrixConstants.HYSTRIX_RESPONSE_SHORT_CIRCUITED, true);
        assertMockEndpointsSatisfied();
        // wait for the circuit to try an other request
        Thread.sleep(500);
        for (int i = 0; i < 2* REQUEST_VOLUME_THRESHOLD; i++) {
            try {
                template.requestBody("direct:start", "Request Body");
                LOG.info("Curcuit has closed");
            } catch (CamelExecutionException e) {
                Thread.sleep(i*100);
                LOG.info("Curcuit will be closed soon "+ e.toString());
            }
        }
        template.requestBody("direct:start", "Request Body");
        getMockEndpoint("mock:result").expectedPropertyReceived(HystrixConstants.HYSTRIX_RESPONSE_SHORT_CIRCUITED, false);
        getMockEndpoint("mock:result").expectedPropertyReceived(HystrixConstants.HYSTRIX_RESPONSE_SUCCESSFUL_EXECUTION, true);
        assertMockEndpointsSatisfied();
    }
    
    @Override
    protected RoutesBuilder createRouteBuilder() throws Exception {
        return route;
    }

 
    
   class HystrixExceptionRoute extends RouteBuilder{
        volatile boolean throwException = true; 
        
        @Override
        public void configure() throws Exception {
            from("direct:start")
            .hystrix()
                .hystrixConfiguration()
                    .executionTimeoutInMilliseconds(100)
                    .circuitBreakerRequestVolumeThreshold(REQUEST_VOLUME_THRESHOLD)
                    .metricsRollingStatisticalWindowInMilliseconds(1000)
                    .circuitBreakerSleepWindowInMilliseconds(2000)
                .end()
                .log("Hystrix processing start: ${threadName}")
                .process(new Processor() {
                    @Override
                    public void process(Exchange exchange) throws Exception {
                        if(throwException){
                            LOG.info("Will throw exception");
                            throw new IOException("Route has failed");
                            //Thread.sleep(200);
                        }else{
                            LOG.info("Will NOT throw exception");
                        }
                    }
                })
                .log("Hystrix processing end: ${threadName}")
            .end()
            .log(HYSTRIX_RESPONSE_SHORT_CIRCUITED
                    +" = ${exchangeProperty."+HYSTRIX_RESPONSE_SHORT_CIRCUITED+"}")
            .to("mock:result");
            
        }
    }
}
