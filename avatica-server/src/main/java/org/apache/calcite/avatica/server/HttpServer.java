/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.avatica.server;

import org.apache.calcite.avatica.remote.Service.RpcMetadataResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Avatica HTTP server.
 */
public class HttpServer {
  private static final Log LOG = LogFactory.getLog(HttpServer.class);

  private Server server;
  private int port = -1;
  private final AvaticaHandler handler;

  @Deprecated
  public HttpServer(Handler handler) {
    this(wrapJettyHandler(handler));
  }

  public HttpServer(AvaticaHandler handler) {
    this(0, handler);
  }

  @Deprecated
  public HttpServer(int port, Handler handler) {
    this(port, wrapJettyHandler(handler));
  }

  public HttpServer(int port, AvaticaHandler handler) {
    this.port = port;
    this.handler = handler;
  }

  private static AvaticaHandler wrapJettyHandler(Handler handler) {
    if (handler instanceof AvaticaHandler) {
      return (AvaticaHandler) handler;
    }
    // Backwards compatibility, noop's the AvaticaHandler interface
    return new DelegatingAvaticaHandler(handler);
  }

  public void start() {
    if (server != null) {
      throw new RuntimeException("Server is already started");
    }

    final QueuedThreadPool threadPool = new QueuedThreadPool();
    threadPool.setDaemon(true);
    server = new Server(threadPool);
    server.manage(threadPool);

    final ServerConnector connector = new ServerConnector(server);
    connector.setIdleTimeout(60 * 1000);
    connector.setSoLingerTime(-1);
    connector.setPort(port);
    server.setConnectors(new Connector[] { connector });

    final HandlerList handlerList = new HandlerList();
    handlerList.setHandlers(new Handler[] { handler, new DefaultHandler() });
    server.setHandler(handlerList);
    try {
      server.start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    port = connector.getLocalPort();

    LOG.info("Service listening on port " + getPort() + ".");

    // Set the information about the address for this server
    try {
      this.handler.setServerRpcMetadata(createRpcServerMetadata(connector));
    } catch (UnknownHostException e) {
      // Failed to do the DNS lookup, bail out.
      throw new RuntimeException(e);
    }
  }

  private RpcMetadataResponse createRpcServerMetadata(ServerConnector connector) throws
      UnknownHostException {
    String host = connector.getHost();
    if (null == host) {
      // "null" means binding to all interfaces, we need to pick one so the client gets a real
      // address and not "0.0.0.0" or similar.
      host = InetAddress.getLocalHost().getHostName();
    }

    final int port = connector.getLocalPort();

    return new RpcMetadataResponse(String.format("%s:%d", host, port));
  }

  public void stop() {
    if (server == null) {
      throw new RuntimeException("Server is already stopped");
    }

    LOG.info("Service terminating.");
    try {
      final Server server1 = server;
      port = -1;
      server = null;
      server1.stop();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void join() throws InterruptedException {
    server.join();
  }

  public int getPort() {
    return port;
  }
}

// End HttpServer.java