package io.fiber.net.http;

import io.fiber.net.common.async.*;
import io.fiber.net.common.utils.BodyBufSubject;
import io.fiber.net.http.impl.ClientHttpExchange;
import io.fiber.net.http.impl.ConnectionPool;
import io.fiber.net.http.impl.HttpConnection;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;

import java.util.Collection;
import java.util.List;

abstract class ExchangeOb extends ClientHttpExchange implements ConnectionPool.ConnFetcher, ClientResponse {
    private int respStatus;
    private HttpHeaders respHeaders;
    final ClientExchange exchange;
    private HttpHost host;
    HttpConnection cn;

    ExchangeOb(ClientExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public void onConnSuccess(HttpConnection connection) {
        BiConsumer<ClientExchange, HttpConnection> peekConn = exchange.peekConn;
        Function<ClientExchange, ByteBuf> reqBufFullFunc = exchange.reqBufFullFunc;
        Function<ClientExchange, Observable<ByteBuf>> reqBodyFunc = exchange.reqBodyFunc;

        if (peekConn != null) {
            try {
                peekConn.accept(exchange, connection);
            } catch (Throwable e) {
                connection.dismiss();
                onNotifyError(e);
                return;
            }
        }

        if (reqBufFullFunc != null) {
            try {
                connection.send(this, reqBufFullFunc.invoke(exchange));
            } catch (Throwable e) {
                connection.dismiss();
                onNotifyError(e);
            }
        } else if (reqBodyFunc != null) {
            try {
                connection.send(this, reqBodyFunc.invoke(exchange), exchange.flushRequest);
            } catch (Throwable e) {
                connection.dismiss();
                onNotifyError(e);
            }
        } else {
            try {
                connection.sendNonBody(this);
            } catch (HttpClientException e) {
                connection.dismiss();
                onNotifyError(e);
            }
        }
        cn = connection;
    }

    @Override
    public void onConnError(Throwable err) {
        onNotifyError(err);
    }

    @Override
    public int connectTimeout() {
        return exchange.connectTimeout;
    }

    @Override
    public HttpHost getHost() {
        if (host == null) {
            host = exchange.hostFetcher.getHttpHost();
        }
        return host;
    }

    @Override
    public HttpHost httpHost() {
        return getHost();
    }

    @Override
    protected HttpHeaders requestHeaders() {
        return exchange.headers();
    }

    @Override
    protected String requestUri() {
        return exchange.uri;
    }

    @Override
    protected io.netty.handler.codec.http.HttpMethod requestMethod() {
        return HttpMethod.valueOf(exchange.method.name());
    }

    @Override
    protected int requestTimeout() {
        return exchange.requestTimeout;
    }

    @Override
    protected long maxBodyLength() {
        return exchange.maxBodyLength;
    }

    @Override
    protected void onResp(int code, HttpHeaders headers) {
        respStatus = code;
        respHeaders = headers;
        respBody = new BodyBufSubject(cn.ioScheduler());
        try {
            onNotifyResp();
        } catch (Throwable e) {
            if (cn != null) {
                cn.dismiss();
                cn = null;
                notifyError(e);
            }
        }
    }

    @Override
    protected final void onSocketErr(Throwable err) {
        onNotifyError(err);
    }

    @Override
    public int status() {
        return respStatus;
    }

    @Override
    public String getHeader(String name) {
        return respHeaders.get(name);
    }

    @Override
    public List<String> getHeaderList(String name) {
        return respHeaders.getAll(name);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return respHeaders.names();
    }

    @Override
    public void discardRespBody() {
        respBody.dismiss();
    }

    @Override
    public Observable<ByteBuf> readRespBodyUnsafe() {
        return respBody;
    }

    @Override
    public Maybe<ByteBuf> readFullRespBody(Scheduler scheduler) {
        if (respBody.getProducerScheduler() == scheduler) {
            return respBody.toMaybe();
        }
        return respBody.toMaybe().notifyOn(scheduler);
    }

    protected abstract void onNotifyResp() throws Throwable;

    protected abstract void onNotifyError(Throwable err);

}