package com.netflix.karyon.servlet.blocking;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.http.DefaultCookie;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.ServerCookieEncoder;
import io.reactivex.netty.protocol.http.server.HttpServerResponse;
import org.simpleframework.http.parse.ContentTypeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Locale;

import static com.netflix.karyon.servlet.blocking.CookieBasedCorrelator.DEFAULT_SESSION_ID_COOKIE_NAME;

/**
 * Implementation of {@link HttpServletResponse} to suit our needs. <br/>
 * Javadocs on each method provides the details of the implementation and call out cases which are not supported.
 *
 * @author Nitesh Kant
 */
public class HttpServletResponseImpl implements HttpServletResponse {

    private static final Logger logger = LoggerFactory.getLogger(HttpServletResponseImpl.class);

    private final HttpServerResponse<ByteBuf> serverResponse;
    private final HttpServletRequestImpl servletRequest;
    private String charsetName;
    private String contentType;
    private Locale locale;

    private static final HttpDateHeaderHandlerImpl HTTP_DATE_HEADER_HANDLER = new HttpDateHeaderHandlerImpl();
    @GuardedBy("this") private ServletOutputStream servletOutputStream;
    @GuardedBy("this") private PrintWriter outputWriter;
    private ByteBuf outputWriterBuffer;
    private ByteBuf outputStreamBuffer;

    public HttpServletResponseImpl(HttpServerResponse<ByteBuf> serverResponse, HttpServletRequestImpl servletRequest) {
        this.serverResponse = serverResponse;
        this.servletRequest = servletRequest;
    }

    @Override
    public void addCookie(Cookie cookie) {
        setHeader(HttpHeaders.Names.SET_COOKIE, ServerCookieEncoder.encode(adaptCookieInstance(cookie)));
    }

    @Override
    public boolean containsHeader(String name) {
        return serverResponse.getHeaders().contains(name);
    }

    @Override
    public String encodeURL(String url) {
        return url; // We do not support url rewriting.
    }

    @Override
    public String encodeRedirectURL(String url) {
        return url;
    }

    @Override
    @Deprecated
    public String encodeUrl(String url) {
        return encodeURL(url);
    }

    @Override
    @Deprecated
    public String encodeRedirectUrl(String url) {
        return encodeRedirectURL(url);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        _sendError(sc, msg);
    }

    @Override
    public void sendError(int sc) throws IOException {
        _sendError(sc, null);
    }

    @Override
    public void sendRedirect(final String location) throws IOException {
        Preconditions.checkNotNull(location, "Redirect location can not be null.");
        ensureResponseNotSent();
        String scheme = servletRequest.getScheme();
        scheme = scheme.toLowerCase();
        String hostName = servletRequest.getServerName();
        int serverPort = servletRequest.getServerPort();
        String redirectLocation = location;
        String locationPrefix;
        if (location.startsWith("/")) {
            locationPrefix = servletRequest.getContextPath();
            redirectLocation = location.substring(1);
        } else {
            locationPrefix = servletRequest.getRequestURI();
            if (locationPrefix.endsWith("/")) {
                locationPrefix = locationPrefix.substring(0, locationPrefix.length() - 1);
            }
        }

        String redirectUrl = scheme + "://" + hostName + ':' + serverPort + locationPrefix + '/' + redirectLocation;
        _setHeader(HttpHeaders.Names.LOCATION, redirectUrl);
        sendResponseIfNotSent();
    }

    @Override
    public void setDateHeader(String name, long date) {
        _setHeader(name, HTTP_DATE_HEADER_HANDLER.convertToDateHeader(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
        _addHeader(name, HTTP_DATE_HEADER_HANDLER.convertToDateHeader(date));
    }

    @Override
    public void setHeader(String name, String value) {
        _setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        _addHeader(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        _setHeader(name, value);
    }

    @Override
    public void addIntHeader(String name, int value) {
        _addHeader(name, value);
    }

    @Override
    public void setStatus(int sc) {
        _setStatus(HttpResponseStatus.valueOf(sc));
    }

    @Override
    @Deprecated
    public void setStatus(int sc, String sm) {
        _setStatus(new HttpResponseStatus(sc, sm));
    }

    @Override
    public String getCharacterEncoding() {
        return _getCharsetNameWithDefaults();
    }

    @Override
    public String getContentType() {
        return _getContentTypeHeaderValue();
    }

    @Override
    public /*Guard for exactly once lazy instantiation of stream*/synchronized ServletOutputStream getOutputStream() throws IOException {
        if (null != servletOutputStream) {
            return servletOutputStream;
        }

        outputStreamBuffer = serverResponse.getAllocator().buffer();
        servletOutputStream = new ServletOutputStream() {
            @Override
            public void write(int b) throws IOException {
                outputStreamBuffer.writeByte(b);
            }

            @Override
            public void flush() throws IOException {
                serverResponse.writeAndFlush(outputStreamBuffer);
            }
        };
        return servletOutputStream;
    }

    @Override
    public /*Guard for exactly once lazy instantiation of writer*/synchronized PrintWriter getWriter() throws IOException {
        if (null != outputWriter) {
            return outputWriter;
        }

        outputWriterBuffer = serverResponse.getAllocator().buffer();
        outputWriter = new PrintWriter(new OutputStreamWriter(new ByteBufOutputStream(outputWriterBuffer),
                                                              _getCharsetNameWithDefaults()), true) {
            @Override
            public void flush() {
                super.flush();
                serverResponse.writeAndFlush(outputWriterBuffer);
            }
        };
        return outputWriter;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        charsetName = charset;
    }

    @Override
    public void setContentLength(int len) {
        // No op, we take care of setting content length appropriately at the end.
    }

    @Override
    public void setContentType(String type) {
        ContentTypeParser parser = new ContentTypeParser();
        parser.parse(type);
        // Here we always store the parsed representation so that overriding can be done from other methods without
        // worrying about whether char encod was part of content type or not.
        charsetName = parser.getCharset(); // Resets the last set value to null if none exists in this type.
        contentType = parser.getType();
    }

    @Override
    public void setBufferSize(int size) {
        ensureResponseNotSent();
        // No Op, netty's buffers are resiable so no need to adapt to buffer size.
    }

    @Override
    public int getBufferSize() {
        return Integer.MAX_VALUE; /// We do not limit the buffer size as of now as netty offers resizable buffers.
    }

    @Override
    public void flushBuffer() throws IOException {
        ensureResponseNotSent();
        sendResponseIfNotSent();
    }

    @Override
    public void resetBuffer() {
        ensureResponseNotSent();
    }

    @Override
    public boolean isCommitted() {
        return serverResponse.isCloseIssued();
    }

    @Override
    public void reset() {
        ensureResponseNotSent();
        serverResponse.getHeaders().clear();
        serverResponse.setStatus(HttpResponseStatus.NO_CONTENT);
    }

    @Override
    public void setLocale(Locale locale) {
        this.locale = locale;
        setHeader(HttpHeaders.Names.CONTENT_LANGUAGE, locale.getLanguage());
        // TODO: Set charset name
    }

    @Override
    public Locale getLocale() {
        return _getLocaleWithDefaults();
    }

    public void sendResponseIfNotSent() throws IOException {
        if (serverResponse.isCloseIssued()) {
            return;
        }

        beforeResponseSend();
        serverResponse.close();
    }

    @VisibleForTesting ByteBuf getOutputStreamBuffer() {
        return outputStreamBuffer;
    }

    @VisibleForTesting ByteBuf getOutputWriterBuffer() {
        return outputWriterBuffer;
    }

    @VisibleForTesting HttpServerResponse<ByteBuf> serverResponse() {
        return serverResponse;
    }

    @VisibleForTesting HttpServletRequestImpl servletRequest() {
        return servletRequest;
    }

    private void _setStatus(HttpResponseStatus sc) {
        serverResponse.setStatus(sc);
    }

    private void _sendError(int responseCode, @Nullable String message) throws IOException {
        ensureResponseNotSent();
        serverResponse.setStatus(HttpResponseStatus.valueOf(responseCode));
        if (null != message) {
            setHeader(HttpHeaders.Names.CONTENT_TYPE, "text/html");
            getWriter().write(message);
            getWriter().flush();
        }
    }

    private void beforeResponseSend() throws IOException {
        flushStreamsAndWriters();

        HttpSession session = servletRequest.getSession();
        if (null != session && session.isNew()) {
            serverResponse.getHeaders().set(HttpHeaders.Names.SET_COOKIE,
                                   ServerCookieEncoder.encode(DEFAULT_SESSION_ID_COOKIE_NAME,
                                                              session.getId()));
        }

        String contentType = _getContentTypeHeaderValue();
        if (null != contentType) {
            serverResponse.getHeaders().set(HttpHeaders.Names.CONTENT_TYPE, contentType);
        }

        if (null != charsetName) {
            serverResponse.getHeaders().set(HttpHeaders.Names.CONTENT_ENCODING, charsetName);
        }
    }

    private void flushStreamsAndWriters() throws IOException {
        if (null != outputWriter) {
            outputWriter.flush();
        }
        if (null != servletOutputStream) {
            servletOutputStream.flush();
        }
    }

    private String _getContentTypeHeaderValue() {
        // If content type is not set by setContentType() then this returns null, else, concatenate character encoding,
        // if available as per the HTTP spec
        String toReturn = contentType;

        if (null != toReturn && null != charsetName) {
            toReturn = toReturn + "; charset=" + charsetName;
        }

        return toReturn;
    }

    private Locale _getLocaleWithDefaults() {
        return null != locale ? locale : Locale.getDefault();
    }

    private String _getCharsetNameWithDefaults() {
        return null != charsetName ? charsetName : HttpServletRequestImpl.DEFAULT_CHARACTER_ENCODING;
    }

    private void _setHeader(String name, Object value) {
        serverResponse.getHeaders().set(name, value);
    }

    private void _addHeader(String name, Object value) {
        serverResponse.getHeaders().add(name, value);
    }

    private void ensureResponseNotSent() {
        if (serverResponse.isCloseIssued()) {
            IllegalStateException e = new IllegalStateException("Response is already sent, no modifications to response are allowed now.");
            logger.error("Response is already sent, no modifications to response are allowed now.", e);
            throw e;
        }
    }

    private static io.netty.handler.codec.http.Cookie adaptCookieInstance(Cookie cookie) {
        DefaultCookie toReturn = new DefaultCookie(cookie.getName(), cookie.getValue());
        toReturn.setComment(cookie.getComment());
        toReturn.setSecure(cookie.getSecure());
        toReturn.setDomain(cookie.getDomain());
        toReturn.setPath(cookie.getPath());
        toReturn.setMaxAge(cookie.getMaxAge());
        toReturn.setVersion(cookie.getVersion());
        return toReturn;
    }
}
