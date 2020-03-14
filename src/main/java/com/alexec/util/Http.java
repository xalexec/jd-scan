package com.alexec.util;

import com.alexec.Storage;
import com.alexec.model.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.*;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.config.*;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpConnectionFactory;
import org.apache.http.conn.ManagedHttpClientConnection;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.*;
import org.apache.http.impl.io.DefaultHttpRequestWriterFactory;
import org.apache.http.io.HttpMessageParser;
import org.apache.http.io.HttpMessageParserFactory;
import org.apache.http.io.HttpMessageWriterFactory;
import org.apache.http.io.SessionInputBuffer;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicLineParser;
import org.apache.http.message.LineParser;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.CharArrayBuffer;
import org.apache.http.util.EntityUtils;

import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Map;

@Slf4j
public class Http {
    final static CloseableHttpClient httpclient = Http.getInstance();

    // region http 请求工具
    public final static Response getResponse(String urlString) {
        return getResponse(urlString, null, null);
    }

    public final static Response getResponse(String urlString, Map<String, String> body, Map<String, String> header) {
        HttpUriRequest httpUriRequest = null;
        if (body != null) {
            // post
            StringBuilder stringBuilder = new StringBuilder();
            for (Map.Entry<String, String> entry : body.entrySet()) {
                String encodedName = entry.getKey();
                String encodedValue = entry.getValue();
                if (stringBuilder.length() > 0) {
                    stringBuilder.append("&");
                }
                stringBuilder.append(encodedName);
                if (encodedValue != null) {
                    stringBuilder.append("=");
                    stringBuilder.append(encodedValue);
                }
            }
            httpUriRequest = RequestBuilder.post(urlString).setEntity(
                    new StringEntity(stringBuilder.toString(), ContentType.APPLICATION_FORM_URLENCODED)).build();
        } else {
            // get
            httpUriRequest = RequestBuilder.get(urlString).build();
        }
        httpUriRequest.setHeader("user-agent", Storage.config.getUserAgent());
        if (header != null) {
            for (Map.Entry<String, String> entry : header.entrySet()) {
                httpUriRequest.setHeader(entry.getKey(), entry.getValue());
            }
        }
        try (CloseableHttpResponse res = httpclient.execute(httpUriRequest);) {
            Response response = Response.builder()
                    .header(res.getAllHeaders())
                    .statusCode(res.getStatusLine().getStatusCode())
                    .build();
            Header[] contentType = res.getHeaders("content-type");
            if (contentType == null || contentType.length == 0) {
                return null;
            }

            if ("image/png".equals(contentType[0].getValue())) {
                InputStream inputStream = res.getEntity().getContent();
                response.setBufferedImage(ImageIO.read(inputStream));
            } else {
                response.setBody(EntityUtils.toString(res.getEntity(), "UTF-8"));
            }
            if (log.isDebugEnabled()) {
                log.debug(response.toString());
            }
            return response;
        } catch (ClientProtocolException e) {
            log.error(e.toString());
        } catch (IOException e) {
            log.error(e.toString());
        }
        return null;
    }
    //endregion

    // region 单例
    private static volatile CloseableHttpClient httpClient;


    public static CloseableHttpClient getInstance() {
        if (null == httpClient) {
            synchronized (Http.class) {
                if (null == httpClient) {
                    // Use custom message parser / writer to customize the way HTTP
                    // messages are parsed from and written out to the data stream.
                    HttpMessageParserFactory<HttpResponse> responseParserFactory = new DefaultHttpResponseParserFactory() {

                        @Override
                        public HttpMessageParser<HttpResponse> create(
                                SessionInputBuffer buffer, MessageConstraints constraints) {
                            LineParser lineParser = new BasicLineParser() {

                                @Override
                                public Header parseHeader(final CharArrayBuffer buffer) {
                                    try {
                                        return super.parseHeader(buffer);
                                    } catch (ParseException ex) {
                                        return new BasicHeader(buffer.toString(), null);
                                    }
                                }

                            };
                            return new DefaultHttpResponseParser(
                                    buffer, lineParser, DefaultHttpResponseFactory.INSTANCE, constraints) {

                                @Override
                                protected boolean reject(final CharArrayBuffer line, int count) {
                                    // try to ignore all garbage preceding a status line infinitely
                                    return false;
                                }

                            };
                        }

                    };
                    HttpMessageWriterFactory<HttpRequest> requestWriterFactory = new DefaultHttpRequestWriterFactory();

                    // Use a custom connection factory to customize the process of
                    // initialization of outgoing HTTP connections. Beside standard connection
                    // configuration parameters HTTP connection factory can define message
                    // parser / writer routines to be employed by individual connections.
                    HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory = new ManagedHttpClientConnectionFactory(
                            requestWriterFactory, responseParserFactory);

                    // Client HTTP connection objects when fully initialized can be bound to
                    // an arbitrary network socket. The process of network socket initialization,
                    // its connection to a remote address and binding to a local one is controlled
                    // by a connection socket factory.

                    // SSL context for secure connections can be created either based on
                    // system or application specific properties.
                    SSLContext sslcontext = SSLContexts.createSystemDefault();

                    // Create a registry of custom connection socket factories for supported
                    // protocol schemes.
                    Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                            .register("http", PlainConnectionSocketFactory.INSTANCE)
                            .register("https", new SSLConnectionSocketFactory(sslcontext))
                            .build();

                    // Use custom DNS resolver to override the system DNS resolution.
                    DnsResolver dnsResolver = new SystemDefaultDnsResolver() {

                        @Override
                        public InetAddress[] resolve(final String host) throws UnknownHostException {
                            return super.resolve(host);
                        }

                    };

                    // Create a connection manager with custom configuration.
                    PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager(
                            socketFactoryRegistry, connFactory, dnsResolver);

                    // Create socket configuration
                    SocketConfig socketConfig = SocketConfig.custom()
                            .setTcpNoDelay(true)
                            .build();
                    // Configure the connection manager to use socket configuration either
                    // by default or for a specific host.
                    connManager.setDefaultSocketConfig(socketConfig);
                    //connManager.setSocketConfig(new HttpHost("somehost", 80), socketConfig);
                    // Validate connections after 1 sec of inactivity
                    connManager.setValidateAfterInactivity(1000);

                    // Create message constraints
                    MessageConstraints messageConstraints = MessageConstraints.custom()
                            .setMaxHeaderCount(200)
                            .setMaxLineLength(2000)
                            .build();
                    // Create connection configuration
                    ConnectionConfig connectionConfig = ConnectionConfig.custom()
                            .setMalformedInputAction(CodingErrorAction.IGNORE)
                            .setUnmappableInputAction(CodingErrorAction.IGNORE)
                            .setCharset(Consts.UTF_8)
                            .setMessageConstraints(messageConstraints)
                            .build();
                    // Configure the connection manager to use connection configuration either
                    // by default or for a specific host.
                    connManager.setDefaultConnectionConfig(connectionConfig);
                    //connManager.setConnectionConfig(new HttpHost("somehost", 80), ConnectionConfig.DEFAULT);

                    // Configure total max or per route limits for persistent connections
                    // that can be kept in the pool or leased by the connection manager.
                    connManager.setMaxTotal(50);
                    connManager.setDefaultMaxPerRoute(40);
                    //connManager.setMaxPerRoute(new HttpRoute(new HttpHost("somehost", 80)), 20);

                    // Use custom cookie store if necessary.
                    //CookieStore cookieStore = new BasicCookieStore();
                    // Use custom credentials provider if necessary.
                    CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    // Create global request configuration
                    RequestConfig defaultRequestConfig = RequestConfig.custom()
                            .setCookieSpec(CookieSpecs.DEFAULT)
                            .setExpectContinueEnabled(true)
                            .setTargetPreferredAuthSchemes(Arrays.asList(AuthSchemes.NTLM, AuthSchemes.DIGEST))
                            .setProxyPreferredAuthSchemes(Arrays.asList(AuthSchemes.BASIC))
                            .setSocketTimeout(3000)
                            .setConnectTimeout(3000)
                            .setConnectionRequestTimeout(3000)
                            .build();

                    // Create an HttpClient with the given custom dependencies and configuration.
                    httpClient = HttpClients.custom()
                            .setConnectionManager(connManager)
                            .setDefaultCookieStore(Storage.config.getBasicCookieStore())
                            .setDefaultCredentialsProvider(credentialsProvider)
                            //.setProxy(new HttpHost("myproxy", 8080))
                            .setDefaultRequestConfig(defaultRequestConfig)
                            .build();
                }
            }
        }
        return httpClient;
    }
    // endregion
}
