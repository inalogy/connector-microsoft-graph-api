package com.evolveum.polygon.connector.msgraphapi;

import com.evolveum.polygon.connector.msgraphapi.authentication.MsalTokenProvider;
import com.evolveum.polygon.connector.msgraphapi.authentication.TokenAcquisitionException;
import com.evolveum.polygon.connector.msgraphapi.authentication.TokenProvider;
import com.evolveum.polygon.connector.msgraphapi.util.PolyTrustManager;
import org.apache.http.*;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.client.methods.*;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ByteArrayEntity;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.framework.common.exceptions.*;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.evolveum.polygon.connector.msgraphapi.ObjectProcessing.LOG;
import static com.evolveum.polygon.connector.msgraphapi.ObjectProcessing.TOP;

/**
 * Facade for Microsoft Graph API endpoint.
 * Facilitates HTTP access to Microsoft services.
 */
public class GraphEndpoint {
    private final static String API_ENDPOINT = "graph.microsoft.com/v1.0";
    private final static String GRAPH_SCOPE = "https://graph.microsoft.com/.default";
    //private static final int MAX_THROTTLING_RETRY_COUNT = 3;

    private final MSGraphConfiguration configuration;
    private final URIBuilder uriBuilder;
    private final TokenProvider tokenProvider;
    private SchemaTranslator schemaTranslator;
    private CloseableHttpClient httpClient;
    private Boolean throttling = false;
    private Boolean validateWithCustomAndDefaultTrust = false;

    GraphEndpoint(MSGraphConfiguration configuration) {

        this(configuration,false);
    }

    GraphEndpoint(MSGraphConfiguration configuration, boolean validateWithCustomAndDefaultTrust) {
        this.configuration = configuration;
        this.uriBuilder = createURIBuilder();
        this.validateWithCustomAndDefaultTrust = validateWithCustomAndDefaultTrust;
        this.tokenProvider = createTokenProvider();
        authenticate();
        initHttpClient();
        initSchema();
    }

    GraphEndpoint(
            MSGraphConfiguration configuration,
            TokenProvider tokenProvider,
            CloseableHttpClient httpClient) {
        this.configuration = configuration;
        this.uriBuilder = createURIBuilder();
        this.tokenProvider = tokenProvider;
        this.httpClient = httpClient;
        initSchema();
    }


    public MSGraphConfiguration getConfiguration() {
        return configuration;
    }

    public SchemaTranslator getSchemaTranslator() {
        return schemaTranslator;
    }

    protected void authenticate() {
        acquireAccessToken(false);
    }

    private TokenProvider createTokenProvider() {
        SSLSocketFactory sslSocketFactory = null;
        if (configuration.isValidateWithFailoverTrust() || validateWithCustomAndDefaultTrust) {
            sslSocketFactory = createCustomSSLSocketFactory();
        }
        try {
            return new MsalTokenProvider(configuration, sslSocketFactory);
        } catch (TokenAcquisitionException e) {
            throw mapAuthenticationFailure(e);
        }
    }

    private void initSchema() {
        schemaTranslator = new SchemaTranslator(this);
    }

    protected void initHttpClient() {
        final HttpClientBuilder clientBuilder = HttpClientBuilder.create();
        clientBuilder.setRetryHandler(myRetryHandler);
        clientBuilder.setServiceUnavailableRetryStrategy(new ServiceUnavailableRetryStrategy() {
            @Override
            public boolean retryRequest(HttpResponse response, int executionCount, HttpContext context) {
                return executionCount <= 7 && response.getStatusLine().getStatusCode() >= 500 && response.getStatusLine().getStatusCode() < 600;
            }

            @Override
            public long getRetryInterval() {
                return 3000;
            }
        });
        if (configuration.hasProxy()) {
            LOG.info("Executing request through proxy[{0}]", configuration.getProxyAddress());
            clientBuilder.setProxy(
                    new HttpHost(configuration.getProxyAddress().getAddress(), configuration.getProxyAddress().getPort())
            );
        }

        if(configuration.isValidateWithFailoverTrust()){

        clientBuilder.setSSLSocketFactory(new SSLConnectionSocketFactory(createCustomSSLSocketFactory(),
                new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        return hostname!=null ? hostname.equals(session.getPeerHost()) : false;
                    }
                }));
        }

        httpClient = clientBuilder.build();
    }

    private String acquireAccessToken(boolean forceReacquisition) {
        try {
            return forceReacquisition
                    ? tokenProvider.reacquireToken(GRAPH_SCOPE)
                    : tokenProvider.acquireToken(GRAPH_SCOPE);
        } catch (TokenAcquisitionException e) {
            throw mapAuthenticationFailure(e);
        }
    }

    private RuntimeException mapAuthenticationFailure(TokenAcquisitionException failure) {
        switch (failure.getReason()) {
            case INVALID_CREDENTIAL:
                return new InvalidCredentialException(failure.getMessage());
            case INVALID_TENANT:
            case INVALID_CLIENT:
                return new ConfigurationException(failure.getMessage());
            case AUTHORITY_UNAVAILABLE:
            case PROXY_UNAVAILABLE:
            case NETWORK_UNAVAILABLE:
            case TLS_FAILURE:
                return new ConnectionFailedException(failure.getMessage());
            default:
                return new ConnectorException(failure.getMessage());
        }
    }

    public URIBuilder createURIBuilder() {
        return new URIBuilder().setScheme("https").setHost(API_ENDPOINT);
    }

    public URI getUri(URIBuilder uriBuilder) {
        URI uri;
        try {
            uri = uriBuilder.build();
        } catch (URISyntaxException e) {
            throw new ConnectorException("It is not possible to create URI" + e.getLocalizedMessage(), e);
        }
        return uri;
    }

    HttpRequestRetryHandler myRetryHandler = new HttpRequestRetryHandler() {

        public boolean retryRequest(
                IOException exception,
                int executionCount,
                HttpContext context) {
            if (executionCount >= 10) {
                // Do not retry if over max retry count
                return false;
            }
        /*if (exception instanceof InterruptedIOException) {
            // Timeout
            return false;
        }
        if (exception instanceof UnknownHostException) {
            // Unknown host
            return false;
        }
        if (exception instanceof ConnectTimeoutException) {
            // Connection refused
            return false;
        }
        if (exception instanceof SSLException) {
            // SSL handshake exception
            return false;
        } */

            HttpClientContext clientContext = HttpClientContext.adapt(context);
            HttpRequest request = clientContext.getRequest();
            boolean idempotent = !(request instanceof HttpEntityEnclosingRequest);
            if (idempotent) {
                // Retry if the request is considered idempotent
                return true;
            }

            if (exception instanceof org.apache.http.NoHttpResponseException) {
                LOG.warn("No response from server on " + executionCount + " call");
                return true;
            }

            return false;
        }

    };

    CloseableHttpResponse executeRequest(HttpUriRequest request, boolean useDefaultErrorHandling) {
        if (request == null) {
            throw new InvalidAttributeValueException("Request not provided");
        }
        if (request.getURI().toString().contains("photo")) {
            request.setHeader("Content-Type", "image/jpg");
        }
        else {
            request.setHeader("Content-Type", "application/json");
        }

        request.setHeader("ConsistencyLevel", "eventual");
        CloseableHttpResponse response;
        int retryCount = 0;
        AtomicBoolean authenticationRetried = new AtomicBoolean(false);
        try {
            response = executeAuthenticated(request, authenticationRetried);
            throttling = false;
            if (useDefaultErrorHandling) {
                processResponseErrors(response, false);
            }
            while (throttling) {
                throttling = false;
                LOG.ok("Current retry count: {0}", retryCount);
                if (retryCount >= configuration.getThrottlingRetryCount()) {
                    response.close();
                    throw new ConnectorException("Max retry count for request throttling exceeded! Request was not successful");
                }
                retryCount++;
                Header[] callHeaders = response.getAllHeaders();
                if (callHeaders != null) {
                    for (Header header : callHeaders) {
                        if (header.getName().equals("Retry-After")) {
                            String tmpRaHeadValue = header.getValue();
                            if (tmpRaHeadValue != null && !tmpRaHeadValue.isEmpty()) {
                                long retryAfter = (long) (Float.parseFloat(tmpRaHeadValue) * 1000);
                                long maxWail = (long) (Float.parseFloat(configuration.getThrottlingRetryWait()) * 1000);
                                LOG.ok("Max retry time in ms: {0}", maxWail);
                                LOG.ok("Returned retry time in ms: {0}", retryAfter);
                                if (retryAfter > maxWail) {
                                    response.close();
                                    throw new ConnectorException("Max time for request throttling exceeded! Request was not successful");
                                }
                                response.close();
                                Thread.sleep(retryAfter);
                                LOG.ok("Throttling retry");

                                response = executeAuthenticated(request, authenticationRetried);
                                processResponseErrors(response, false);
                            }
                        }
                    }
                }
            }

            return response;

        } catch (IOException | InterruptedException e) {
            StringBuilder sb = new StringBuilder();
            LOG.ok("The exception type: {0}", e.getClass());
            sb.append("It is not possible to execute request:").append(request.toString()).append(";")
                    .append(e.getLocalizedMessage());
            if (e instanceof IOException) {
                throw new ConnectorIOException(sb.toString(), e);
            } else {

                throw new ConnectorException(sb.toString(), e);
            }
        }
    }

    private CloseableHttpResponse executeAuthenticated(
            HttpUriRequest request, AtomicBoolean authenticationRetried) throws IOException {
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + acquireAccessToken(false));
        CloseableHttpResponse response = httpClient.execute(request);
        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_UNAUTHORIZED) {
            return response;
        }

        response.close();
        if (!authenticationRetried.compareAndSet(false, true)) {
            throw new ConnectionFailedException(
                    "Microsoft Graph rejected the refreshed access token (HTTP 401).");
        }

        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + acquireAccessToken(true));
        CloseableHttpResponse retriedResponse = httpClient.execute(request);
        if (retriedResponse.getStatusLine().getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
            retriedResponse.close();
            throw new ConnectionFailedException(
                    "Microsoft Graph rejected the refreshed access token (HTTP 401).");
        }
        return retriedResponse;
    }


    public void processResponseErrors(CloseableHttpResponse response, boolean maxRetriesExceeded) {
        if (response == null) {
            throw new InvalidAttributeValueException("Response not provided ");
        }
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode >= 200 && statusCode <= 299) {
            return;
        }
        if (statusCode == HttpStatus.SC_UNAUTHORIZED) {
            throw new ConnectionFailedException("Microsoft Graph authentication failed (HTTP 401).");
        }
        /*if (statusCode == 404) {
            //throw new UnknownUidException(message);
            LOG.info("Status code 404 caught in processResponseErrors {0}", response.getStatusLine().getReasonPhrase());
            return;
        }*/
        String responseBody = null;
        try {
            responseBody = EntityUtils.toString(response.getEntity());
        } catch (IOException e) {
            LOG.warn("cannot read response body: " + e, e);
        }
        String maxRetriesExceededMessage = "";
        if (maxRetriesExceeded) {
            maxRetriesExceededMessage = "Maximum retry attempts '" + this.configuration.getPostCreateReadMaxRetryCount() + "' were exceeded!";
        }

        String providerErrorCode = extractProviderErrorCode(responseBody);
        String message = maxRetriesExceededMessage + "HTTP error " + statusCode + " "
                + response.getStatusLine().getReasonPhrase()
                + (providerErrorCode == null ? "" : " (provider code: " + providerErrorCode + ")");
        String classificationText = responseBody == null ? "" : responseBody;
        //LOG.error("{0}", message);
        if (statusCode == 400 && classificationText.contains("The client credentials are invalid")) {
            throw new InvalidCredentialException(message);
        }
        if (statusCode == 400 && classificationText.contains("Another object with the same value for property userPrincipalName already exists.")) {
            throw new AlreadyExistsException(message);
        }

        if ((statusCode == 400 || statusCode == 404) && classificationText.contains("Property netId is invalid") && this.configuration.getTreatNetIdAsAlreadyExists()){
            LOG.info("Treating 'Property netId is invalid' as alreadyExists");
            throw new AlreadyExistsException(message);
        }
        if (statusCode == 400 && classificationText.contains("The specified password does not comply with password complexity requirements.")) {
            throw new InvalidPasswordException(message);
        }
        if (statusCode == 400 && classificationText.contains("Invalid object identifier")) {
            throw new UnknownUidException(message);
        }
        if (statusCode == 400 || statusCode == 405 || statusCode == 406) {
            throw new InvalidAttributeValueException(message);
        }
        if (statusCode == 402 || statusCode == 403 || statusCode == 407) {
            throw new PermissionDeniedException(message);
        }
        if (statusCode == 404 || statusCode == 410) {
            if (classificationText.contains("ImageNotFound"))
                return;
            else {
                LOG.info("Status code 404 or 410 caught in processResponseErrors {0}", message);
                throw new UnknownUidException(message);
            }
        }
        if (statusCode == 408) {
            throw new OperationTimeoutException(message);
        }
        if (statusCode == 409) {
            throw new AlreadyExistsException(message);
        }
        if (statusCode == 412) {
            throw new PreconditionFailedException(message);
        }
        if (statusCode == 418) {
            throw new UnsupportedOperationException("Sorry, no cofee: " + message);
        }
        if (statusCode == 429) {
            LOG.warn("Request returned with status code 429 which means an api call limit was reached.");
            throttling = true;
            return;
        }

        throw new ConnectorException(message);
    }

    private String extractProviderErrorCode(String responseBody) {
        if (StringUtil.isBlank(responseBody)) {
            return null;
        }
        try {
            JSONObject error = new JSONObject(responseBody).optJSONObject("error");
            if (error == null) {
                return null;
            }
            String code = error.optString("code", null);
            return StringUtil.isBlank(code) || code.length() > 128 ? null : code;
        } catch (RuntimeException e) {
            return null;
        }
    }


    protected JSONObject callRequest(HttpRequestBase request, boolean parseResult) {
        if (request == null) {
            throw new InvalidAttributeValueException("Request not provided or empty");
        }
        LOG.ok("callRequest execution");
        String result = null;
        request.setHeader("ConsistencyLevel", "eventual");

        if (LOG.isOk()) {
            LOG.ok("URL in request: {0}", request.getRequestLine().getUri());
        }
        if (request.getURI().toString().contains("photo")){
            // this uri check is necessary otherwise inspecting of shadow w/o photo returns 404
            return callRequestPhoto(request);
        }
        if (request.getURI().toString().contains("roleAssignments")){
            // this uri check is necessary otherwise inspecting of newly created user role assignments returns 404
            return roleAssignments(request);
        }
        try (CloseableHttpResponse response = executeRequest(request, true)) {
            processResponseErrors(response, false);
            if (response.getStatusLine().getStatusCode() == 204) {
                LOG.ok("204 - No Content ");
                return null;
            } else if (response.getStatusLine().getStatusCode() == 200 && !parseResult) {
                LOG.ok("200 - OK");
                return null;
            }

            result = EntityUtils.toString(response.getEntity());
            if (!parseResult) {
                return null;
            }
            return new JSONObject(result);
        } catch (IOException e) {
            throw new ConnectorIOException();
        }

    }

    private JSONObject roleAssignments(HttpRequestBase request) {
        int roleAssignmentsRetryCount = 0;
        do {
            try (CloseableHttpResponse response = executeRequest(request,false)) {
                if (response.getStatusLine().getStatusCode() == 404) {
                    roleAssignmentsRetryCount++;
                    if (roleAssignmentsRetryCount >= this.configuration.getPostCreateReadMaxRetryCount()) {
                        processResponseErrors(response, true);
                        return null;
                    }
                } else {
                    processResponseErrors(response, false);
                    if (response.getStatusLine().getStatusCode() == 204) {
                        LOG.ok("204 - No Content ");
                        return null;
                    }

                    String result = EntityUtils.toString(response.getEntity());
                    return new JSONObject(result);
                }
            } catch (IOException e) {
                throw new ConnectorIOException();
            }

            try {
                long sleepTime = this.configuration.getPostCreateReadRetryBaseDelayMs() * (1L << (roleAssignmentsRetryCount - 1));
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } while (roleAssignmentsRetryCount < this.configuration.getPostCreateReadMaxRetryCount());
        return null;
    }

    private JSONObject callRequestPhoto(HttpRequestBase request) {
        String result;

        try (CloseableHttpResponse response = executeRequest(request, false)) {
            if (response.getStatusLine().getStatusCode() == 404){
                return new JSONObject(Collections.singletonMap("data", null));
            }
            else {
                processResponseErrors(response, false);
                result = java.util.Base64.getEncoder().encodeToString(EntityUtils.toByteArray(response.getEntity()));
                return new JSONObject(Collections.singletonMap("data", result));
            }
        } catch (IOException e) {
            throw new ConnectorIOException();
        }
    }

    public JSONObject callRequest(HttpEntityEnclosingRequestBase request, JSONObject json, Boolean parseResult) {
        LOG.info("request URI: {0}", request.getURI());

        HttpEntity entity;
        byte[] jsonByte;
        try {
            jsonByte = json.toString().getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new ConnectorIOException("Failed to encode the request payload as UTF-8.", e);
        }
        entity = new ByteArrayEntity(jsonByte);

        request.setEntity(entity);
        // execute request
        try (CloseableHttpResponse response = executeRequest(request, true)) {
            processResponseErrors(response, false);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 201) {
                LOG.ok("201 - Created");
            } else if (statusCode == 204) {
                LOG.ok("204 - no content");
            } else {
                LOG.info("statuscode - {0}", statusCode);
            }

            if (!parseResult) {
                return null;
            }

            HttpEntity responseEntity = response.getEntity();
            byte[] byteResult = EntityUtils.toByteArray(responseEntity);
            String result = new String(byteResult, Charset.forName("ISO-8859-2"));
            return new JSONObject(result);
        } catch (IOException e) {
            throw new ConnectorIOException("Failed to read the Microsoft Graph response.", e);
        }
    }

    protected JSONObject executeGetRequest(String path, String customQuery, OperationOptions options) {
        LOG.info("executeGetRequest path {0}, customQuery {1}, options: {2}", path, customQuery, options);
        final URIBuilder uribuilder = createURIBuilder().setPath(path);

        if (customQuery != null) {
            uribuilder.setCustomQuery(customQuery);
            LOG.ok("setCustomQuery {0}", uribuilder);
        }

        try {
            URI uri = uribuilder.build();
            LOG.info("uri {0}", uri);
            HttpRequestBase request = new HttpGet(uri);
            return callRequest(request, true);

        } catch (URISyntaxException e) {
            StringBuilder sb = new StringBuilder();
            sb.append("It was not possible create URI from UriBuilder:").append(uriBuilder).append(";")
                    .append(e.getLocalizedMessage());
            throw new ConnectorException(sb.toString(), e);
        }
    }

    // If the resource indicated by the "path" argument does not support paging, the "paging" argument must be false
    protected JSONArray executeListRequest(String path, String customQuery, OperationOptions options, boolean paging) {
        JSONArray value = new JSONArray();
        executeListRequest(path, customQuery, options, paging, (op, object) -> {
            value.put(object);
            return true;
        });
        return value;
    }

    // If the resource indicated by the "path" argument does not support paging, the "paging" argument must be false
    protected void executeListRequest(String path, String customQuery, OperationOptions options,
                                      boolean paging, ObjectProcessing.JSONObjectHandler handler) {
        LOG.info("executeGetRequest path {0}, customQuery {1}, options: {2}", path, customQuery, options);
        final URIBuilder uribuilder = createURIBuilder().setPath(path);

        StringBuilder query = new StringBuilder();
        if (customQuery != null) {
            query.append(customQuery);
        }

        if (paging) {
            String pageSize = configuration.getPageSize();
            if (StringUtil.isNotBlank(pageSize)) {
                if (customQuery != null) {
                    query.append("&");
                }
                query.append(TOP);
                query.append("=");
                query.append(pageSize);
            }
        }

        if (query.length() > 0) {
            uribuilder.setCustomQuery(query.toString());
            LOG.ok("setCustomQuery {0}", uribuilder);
        }

        URI uri;
        try {
            uri = uribuilder.build();
        } catch (URISyntaxException e) {
            StringBuilder sb = new StringBuilder();
            sb.append("It was not possible create URI from UriBuilder:").append(uriBuilder).append(";")
                    .append(e.getLocalizedMessage());
            throw new ConnectorException(sb.toString(), e);
        }

        // Handle paging if the response contains @odata.nextLink
        do {
            HttpRequestBase request = new HttpGet(uri);
            LOG.info("request {0}", request);

            final JSONObject response = callRequest(request, true);

            if (hasNextLink(response)) {
                String nextLink = getNextLink(response);
                LOG.info("nextLink: {0}", nextLink);
                uri = URI.create(nextLink);
            } else {
                LOG.info("No nextLink defined, final page");
                uri = null;
            }
            if (hasJSONArray(response)) {
                JSONArray jsonArray = getJSONArray(response);
                for (int i = 0; i < jsonArray.length(); i++) {
                    if (!handler.handle(options, jsonArray.getJSONObject(i))) {
                        return;
                    }
                }
            } else {
                LOG.info("nextLinkJson contained no value object or the object was null");
            }
        } while (uri != null);
    }

    private boolean hasJSONArray(JSONObject object) {
        return object.has("value") && object.get("value") != null;
    }

    private JSONArray getJSONArray(JSONObject object) {
        return object.getJSONArray("value");
    }

    private boolean hasNextLink(JSONObject object) {
        return object.has("@odata.nextLink") && object.getString("@odata.nextLink") != null && !object.getString("@odata.nextLink").isEmpty();
    }

    private String getNextLink(JSONObject object) {
        return object.getString("@odata.nextLink");
    }

    protected void callRequestNoContent(HttpEntityEnclosingRequestBase request, Set<Attribute> attributes, JSONObject jsonObject) {
        if (request == null) {
            throw new InvalidAttributeValueException("Request not provided or empty");
        }


        HttpEntity entity = null;
        JSONObject json = new JSONObject();

        try {
            entity = new ByteArrayEntity(jsonObject.toString().getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            StringBuilder sb = new StringBuilder();
            sb.append("Unsupported Encoding when creating object in Box").append(";").append(e.getLocalizedMessage());
            throw new ConnectorException(sb.toString(), e);
        }

        request.setEntity(entity);

        try (CloseableHttpResponse response = executeRequest(request, true)) {
            processResponseErrors(response, false);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 204) {
                LOG.ok("204 - No content, Update was successful");
            } else {
                LOG.error("Not updated, statusCode: {0}", statusCode);
            }
        } catch (IOException e) {
            throw new ConnectorIOException();
        }


    }

    protected void callRequestNoContentNoJson(HttpEntityEnclosingRequestBase request, List<JSONObject> jsonObjects) {
        if (request == null) {
            throw new InvalidAttributeValueException("Request not provided or empty");
        }

        // Azure AD and SharePoint Online attributes cannot be updated together. Therefore, it is necessary to update them separately.
        // Reference: https://learn.microsoft.com/en-us/graph/api/user-update?view=graph-rest-1.0&tabs=http
        for (JSONObject jsonObject : jsonObjects) {
            HttpEntity entity = null;

            try {
                entity = new ByteArrayEntity(jsonObject.toString().getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                throw new ConnectorIOException(e);
            }
            request.setEntity(entity);

            try (CloseableHttpResponse response = executeRequest(request, true)) {
                processResponseErrors(response, false);
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == 204) {
                    LOG.ok("204 - No content, Update was successful");
                } else {
                    LOG.error("Not updated, statusCode: {0}", statusCode);
                }
            } catch (IOException e) {
                throw new ConnectorIOException(e);
            }

        }
    }

    private SSLSocketFactory createCustomSSLSocketFactory() {

        LOG.ok("Initializing custom SSLSocketFactory method");


        SSLContext sslContext;
        try {

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);

            X509TrustManager defaultTm = null;

            for (TrustManager tm : trustManagerFactory.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    defaultTm = (X509TrustManager) tm;
                    break;
                }
            }

            X509TrustManager customManager = buildCustomManager(configuration.getPathToFailoverTrustStore());

            PolyTrustManager polyTrustManager;
            if(validateWithCustomAndDefaultTrust){

                 polyTrustManager = new PolyTrustManager(defaultTm, customManager) ;
            } else {

                 polyTrustManager = new PolyTrustManager(customManager) ;
            }


            LOG.info("Attempt to initialize custom SSL context, using custom trust manager");
            sslContext = SSLContext.getInstance("TLS");

            sslContext.init(null, new TrustManager[]{polyTrustManager}, null);

        } catch (IOException | NoSuchAlgorithmException | CertificateException | KeyStoreException | KeyManagementException e) {

            LOG.error("Exception while loading custom trustStore" + e);

            return null;
        }

        LOG.info("SSL context initialize, about to return custom SSL socket factory");
        return sslContext.getSocketFactory();
    }

    private X509TrustManager buildCustomManager(String path) throws KeyStoreException, IOException, CertificateException,
            NoSuchAlgorithmException {

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());;

        FileInputStream trustStoreIs = new FileInputStream(path);

        /// Providing empty password ro read CA certificates
        keyStore.load(trustStoreIs, null);
        trustStoreIs.close();

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);


        X509TrustManager customManager = null;
        for (TrustManager tm : trustManagerFactory.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                customManager = (X509TrustManager) tm;
                break;
            }
        }

        return customManager;
    }

    public void close() {
        ConnectorIOException closeFailure = null;
        try {
            if (httpClient != null) {
                httpClient.close();
            }
        } catch (IOException e) {
            closeFailure = new ConnectorIOException("Failed to close the Microsoft Graph HTTP client.", e);
        } finally {
            tokenProvider.close();
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }
}
