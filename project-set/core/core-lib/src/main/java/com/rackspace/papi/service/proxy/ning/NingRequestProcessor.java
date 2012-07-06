package com.rackspace.papi.service.proxy.ning;

import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;
import static com.rackspace.papi.http.Headers.HOST;
import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ning.http.client.RequestBuilder;
import com.rackspace.papi.http.proxy.common.AbstractRequestProcessor;
import com.rackspace.papi.service.proxy.TargetHostInfo;
import java.nio.ByteBuffer;
import javax.servlet.ServletInputStream;

/**
 * Process a request to copy over header values, query string parameters, and
 * request body as necessary.
 *
 */
class NingRequestProcessor extends AbstractRequestProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(NingRequestProcessor.class);
    private final URI targetHost;
    private final HttpServletRequest request;
    private Pattern delimiter = Pattern.compile("&");
    private Pattern pair = Pattern.compile("=");
    private final String targetUrl;
    private final RequestBuilder builder;

    public static class RequestBody implements Body {

        private final HttpServletRequest request;
        private final ServletInputStream stream;

        public RequestBody(HttpServletRequest request) throws IOException {
            this.request = request;
            stream = request.getInputStream();
        }

        @Override
        public long getContentLength() {

            return request.getContentLength();
        }

        @Override
        public long read(ByteBuffer bb) throws IOException {
            if (stream == null) {
                return -1;
            }
            
            int capacity = bb.capacity();
            /*
            int readData = stream.read();
            int count = 0;
            while (readData != -1 && count < capacity) {
                count++;
                bb.put((byte) readData);
                readData = stream.read();
            }
            return count == 0? -1: count;
            * 
            */
            
            byte[] data = new byte[capacity]; 
            int read = stream.read(data, 0, capacity); 
            if (read > 0) { 
                bb.put(data, 0, read); 
            } 
            
            return read;
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }

    public static class RequestBodyGenerator implements BodyGenerator {

        private final HttpServletRequest request;

        public RequestBodyGenerator(HttpServletRequest request) {
            this.request = request;
        }

        @Override
        public Body createBody() throws IOException {
            return new RequestBody(request);
        }
    }

    public NingRequestProcessor(HttpServletRequest request, TargetHostInfo host) throws IOException {
        this.builder = new RequestBuilder(request.getMethod());
        this.targetHost = host.getProxiedHostUri();
        this.targetUrl = host.getProxiedHostUrl().toExternalForm() + request.getRequestURI();
        this.request = request;
    }

    private void setRequestParameters() {
        final String queryString = request.getQueryString();

        if (queryString != null && queryString.length() > 0) {
            String[] params = delimiter.split(queryString);

            for (String param : params) {
                String[] paramPair = pair.split(param);
                if (paramPair.length == 2) {
                    String paramValue = paramPair[1];
                    try {
                        paramValue = URLDecoder.decode(paramValue, "UTF-8");
                    } catch (IllegalArgumentException ex) {
                        LOG.warn("Error decoding query parameter named: " + paramPair[0] + " value: " + paramValue, ex);
                    } catch (UnsupportedEncodingException ex) {
                        LOG.warn("Error decoding query parameter named: " + paramPair[0] + " value: " + paramValue, ex);
                    }
                    builder.addQueryParameter(paramPair[0], paramValue);
                }
            }
        }
    }

    /**
     * Scan header values and manipulate as necessary. Host header, if provided,
     * may need to be updated.
     *
     * @param headerName
     * @param headerValue
     * @return
     */
    private String processHeaderValue(String headerName, String headerValue) {
        String result = headerValue;

        // In case the proxy host is running multiple virtual servers,
        // rewrite the Host header to ensure that we get content from
        // the correct virtual server
        if (headerName.equalsIgnoreCase(HOST.toString())) {
            result = targetHost.getHost() + ":" + targetHost.getPort();
        }

        return result;
    }

    /**
     * Copy header values from source request to the http method.
     *
     * @param method
     */
    private void setHeaders() {
        final Enumeration<String> headerNames = request.getHeaderNames();

        while (headerNames.hasMoreElements()) {
            String header = headerNames.nextElement();

            if (excludeHeader(header)) {
               continue;
            }
            
            Enumeration<String> values = request.getHeaders(header);
            while (values.hasMoreElements()) {
                String value = values.nextElement();
                builder.addHeader(header, processHeaderValue(header, value));
            }
        }
    }

    private boolean canHaveBody() {
        return !("GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod()) || "TRACE".equalsIgnoreCase(request.getMethod()));
    }

    private RequestBodyGenerator getData() throws IOException {
        if (!canHaveBody()) {
            return null;
        }

        return new RequestBodyGenerator(request);
    }

    public RequestBuilder process() throws IOException {
        builder.setUrl(targetUrl);
        setRequestParameters();
        setHeaders();
        RequestBodyGenerator data = getData();
        if (data != null) {
            builder.setBody(data);
        }

        return builder;
    }
}