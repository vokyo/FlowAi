package com.vokyo.backend.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTests {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void propagatesValidTraceIdThroughHeaderAttributeAndMdcThenCleansMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.addHeader(TraceIds.HEADER, "upstream-trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcTraceId = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                mdcTraceId.set(MDC.get(TraceIds.MDC_KEY))
        );

        assertThat(response.getHeader(TraceIds.HEADER)).isEqualTo("upstream-trace-123");
        assertThat(request.getAttribute(TraceIds.REQUEST_ATTRIBUTE))
                .isEqualTo("upstream-trace-123");
        assertThat(mdcTraceId.get()).isEqualTo("upstream-trace-123");
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.addHeader(TraceIds.HEADER, "unsafe trace value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        assertThat(response.getHeader(TraceIds.HEADER))
                .matches("[a-f0-9]{32}")
                .isNotEqualTo("unsafe trace value");
    }
}
