/*
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
package org.apache.camel.component.seda;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.ContextTestSupport;
import org.apache.camel.builder.RouteBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SedaInOutWithErrorTest extends ContextTestSupport {

    @Test
    public void testInOutWithError() throws Exception {
        getMockEndpoint("mock:result").expectedMessageCount(0);

        CamelExecutionException e = assertThrows(CamelExecutionException.class, () -> template.requestBody("direct:start", "Hello World", String.class),
                "Should have thrown an exception");

        assertIsInstanceOf(IllegalArgumentException.class, e.getCause());
        assertEquals("Damn I cannot do this", e.getCause().getMessage());
        assertMockEndpointsSatisfied();
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:start").to("seda:foo");

                from("seda:foo").transform(constant("Bye World"))
                        .throwException(new IllegalArgumentException("Damn I cannot do this")).to("mock:result");
            }
        };
    }
}
