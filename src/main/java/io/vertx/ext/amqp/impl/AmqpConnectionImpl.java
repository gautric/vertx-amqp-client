/*
 * Copyright (c) 2018-2019 The original author or authors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *        The Eclipse Public License is available at
 *        http://www.eclipse.org/legal/epl-v10.html
 *
 *        The Apache License v2.0 is available at
 *        http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.ext.amqp.impl;

import io.vertx.core.*;
import io.vertx.ext.amqp.*;
import io.vertx.proton.*;
import io.vertx.proton.impl.ProtonConnectionImpl;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.TerminusDurability;
import org.apache.qpid.proton.amqp.messaging.TerminusExpiryPolicy;
import org.apache.qpid.proton.engine.EndpointState;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AmqpConnectionImpl implements AmqpConnection {

  public static final String PRODUCT = "vertx-amqp-client";
  public static final Symbol PRODUCT_KEY = Symbol.valueOf("product");

  private final AmqpClientOptions options;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<ProtonConnection> connection = new AtomicReference<>();
  private final Context context;

  private final List<AmqpSender> senders = new CopyOnWriteArrayList<>();
  private final List<AmqpReceiver> receivers = new CopyOnWriteArrayList<>();

  /**
   * Access protected by monitor lock.
   */
  private Handler<Void> endHandler;

  AmqpConnectionImpl(Context context, AmqpClientImpl client, AmqpClientOptions options,
    ProtonClient proton, Handler<AsyncResult<AmqpConnection>> connectionHandler) {
    this.options = options;
    this.context = context;

    runOnContext(x -> connect(client,
      Objects.requireNonNull(proton, "proton cannot be `null`"),
      Objects.requireNonNull(connectionHandler, "connection handler cannot be `null`"))
    );
  }

  private void connect(AmqpClientImpl client, ProtonClient proton,
    Handler<AsyncResult<AmqpConnection>> connectionHandler) {
    proton
      .connect(options, options.getHost(), options.getPort(), options.getUsername(), options.getPassword(),
        ar -> {
          // Called on the connection context.

          if (ar.succeeded()) {
            if (!this.connection.compareAndSet(null, ar.result())) {
              connectionHandler.handle(Future.failedFuture("Unable to connect - already holding a connection"));
              return;
            }

            Map<Symbol, Object> map = new HashMap<>();
            map.put(AmqpConnectionImpl.PRODUCT_KEY, AmqpConnectionImpl.PRODUCT);
            if (options.getContainerId() != null) {
              this.connection.get().setContainer(options.getContainerId());
            }

            if (options.getVirtualHost() != null) {
              this.connection.get().setHostname(options.getVirtualHost());
            }

            this.connection.get()
              .setProperties(map)
              .disconnectHandler(ignored -> onEnd())
              .closeHandler(ignored -> {
                try {
                  onDisconnect();
                } finally {
                  onEnd();
                }
              })
              .openHandler(conn -> {
                if (conn.succeeded()) {
                  client.register(this);
                  closed.set(false);
                  connectionHandler.handle(Future.succeededFuture(this));
                } else {
                  closed.set(true);
                  connectionHandler.handle(conn.mapEmpty());
                }
              });

            this.connection.get().open();
          } else {
            connectionHandler.handle(ar.mapEmpty());
          }
        });
  }

  /**
   * Must be called on context.
   */
  private void onDisconnect() {
    ProtonConnection conn = connection.getAndSet(null);
    if (conn != null) {
      try {
        conn.close();
      } finally {
        conn.disconnect();
      }
    }
  }

  /**
   * Must be called on context.
   */
  private void onEnd() {
    Handler<Void> handler;
    synchronized (this) {
      handler = endHandler;
      endHandler = null;
    }
    if (handler != null && !closed.get()) {
      handler.handle(null);
    }
  }

  void runOnContext(Handler<Void> action) {
    context.runOnContext(action);
  }

  void runWithTrampoline(Handler<Void> action) {
    if (Vertx.currentContext() == context) {
      action.handle(null);
    } else {
      runOnContext(action);
    }
  }

  /**
   * Must be called on context.
   */
  private boolean isLocalOpen() {
    ProtonConnection conn = this.connection.get();
    return conn != null
      && ((ProtonConnectionImpl) conn).getLocalState() == EndpointState.ACTIVE;
  }

  /**
   * Must be called on context.
   */
  private boolean isRemoteOpen() {
    ProtonConnection conn = this.connection.get();
    return conn != null
      && ((ProtonConnectionImpl) conn).getRemoteState() == EndpointState.ACTIVE;
  }

  @Override
  public synchronized AmqpConnection endHandler(Handler<Void> endHandler) {
    this.endHandler = endHandler;
    return this;
  }

  @Override
  public AmqpConnection close(Handler<AsyncResult<Void>> done) {
    context.runOnContext(ignored -> {
      List<Future> futures = new ArrayList<>();
      ProtonConnection actualConnection = connection.get();
      if (actualConnection == null || (closed.get() && (!isLocalOpen() && !isRemoteOpen()))) {
        if (done != null) {
          done.handle(Future.succeededFuture());
        }
        return;
      } else {
        closed.set(true);
      }
      synchronized (this) {
        senders.forEach(sender -> {
          Future<Void> future = Future.future();
          futures.add(future);
          sender.close(future);
        });
        receivers.forEach(receiver -> {
          Future<Void> future = Future.future();
          futures.add(future);
          receiver.close(future);
        });
      }

      CompositeFuture.join(futures).setHandler(result -> {
        Future<Void> future = Future.future();
        if (done != null) {
          future.setHandler(done);
        }
        if (actualConnection.isDisconnected()) {
          future.complete();
        } else {
          try {
            actualConnection
              .closeHandler(cleanup -> {
                onDisconnect();
                future.handle(cleanup.mapEmpty());
              })
              .close();
          } catch (Exception e) {
            future.fail(e);
          }
        }
      });
    });

    return this;
  }

  void unregister(AmqpSender sender) {
    senders.remove(sender);
  }

  void unregister(AmqpReceiver receiver) {
    receivers.remove(receiver);
  }

  @Override
  public AmqpConnection createReceiver(String address, Handler<AmqpMessage> handler,
    Handler<AsyncResult<AmqpReceiver>> completionHandler) {
    return createReceiver(address, null, handler, completionHandler);
  }

  @Override
  public AmqpConnection createDynamicReceiver(Handler<AsyncResult<AmqpReceiver>> completionHandler) {
    return createReceiver(null, new AmqpReceiverOptions().setDynamic(true), null, completionHandler);
  }

  @Override
  public AmqpConnection createReceiver(String address, Handler<AsyncResult<AmqpReceiver>> completionHandler) {
    ProtonLinkOptions opts = new ProtonLinkOptions();

    runWithTrampoline(x -> {
      ProtonReceiver receiver = connection.get().createReceiver(address, opts);
      new AmqpReceiverImpl(
        Objects.requireNonNull(address, "The address must not be `null`"),
        this, false, receiver, null,
        Objects.requireNonNull(completionHandler, "The completion handler must not be `null`"));
    });
    return this;
  }

  @Override
  public AmqpConnection createReceiver(String address, AmqpReceiverOptions receiverOptions,
    Handler<AmqpMessage> handler,
    Handler<AsyncResult<AmqpReceiver>> completionHandler) {
    ProtonLinkOptions opts = new ProtonLinkOptions();
    if (receiverOptions != null) {
      opts
        .setDynamic(receiverOptions.isDynamic())
        .setLinkName(receiverOptions.getLinkName());
    }

    runWithTrampoline(v -> {
      ProtonReceiver receiver = connection.get().createReceiver(address, opts);

      if (receiverOptions != null) {
        if (receiverOptions.getQos() != null) {
          receiver.setQoS(ProtonQoS.valueOf(receiverOptions.getQos().toUpperCase()));
        }

        List<String> desired = receiverOptions.getDesiredCapabilities();
        List<String> provided = receiverOptions.getCapabilities();

        receiver.setDesiredCapabilities(desired.stream().map(Symbol::valueOf).toArray(Symbol[]::new));
        receiver.setOfferedCapabilities(provided.stream().map(Symbol::valueOf).toArray(Symbol[]::new));

        configureTheSource(receiverOptions, receiver);
      }

      new AmqpReceiverImpl(address, this, receiverOptions != null && receiverOptions.isDurable(),
        receiver, handler, completionHandler);
    });
    return this;
  }

  private void configureTheSource(AmqpReceiverOptions receiverOptions, ProtonReceiver receiver) {
    org.apache.qpid.proton.amqp.messaging.Source source = (org.apache.qpid.proton.amqp.messaging.Source) receiver
      .getSource();
    if (receiverOptions.isDurable()) {
      source.setExpiryPolicy(TerminusExpiryPolicy.NEVER);
      source.setDurable(TerminusDurability.UNSETTLED_STATE);
    } else {
      // Check if we have individual values, not the in this case the receiver won't be considered as durable and the
      // "close" method will close the link and not detach.
      if (receiverOptions.getTerminusDurability() != null) {
        source.setDurable(TerminusDurability.valueOf(receiverOptions.getTerminusDurability().toUpperCase()));
      }
      if (receiverOptions.getTerminusExpiryPolicy() != null) {
        source
          .setExpiryPolicy(TerminusExpiryPolicy.valueOf(receiverOptions.getTerminusExpiryPolicy()));
      }
    }
  }

  @Override
  public AmqpConnection createSender(String address, Handler<AsyncResult<AmqpSender>> completionHandler) {
    Objects.requireNonNull(address, "The address must be set");
    return createSender(address, new AmqpSenderOptions(), completionHandler);
  }

  @Override
  public AmqpConnection createSender(String address, AmqpSenderOptions options, Handler<AsyncResult<AmqpSender>> completionHandler) {
    if (address == null  && ! options.isDynamic()) {
      throw new IllegalArgumentException("Address must be set if the link is not dynamic");
    }

    Objects.requireNonNull(completionHandler, "The completion handler must be set");
    runWithTrampoline(x -> {

      ProtonSender sender;
      if (options != null) {
        ProtonLinkOptions opts = new ProtonLinkOptions();
        opts.setLinkName(options.getLinkName());
        opts.setDynamic(options.isDynamic());

        sender = connection.get().createSender(address, opts);
        sender.setAutoDrained(options.isAutoDrained());
        sender.setAutoSettle(options.isAutoSettle());
      } else {
        sender = connection.get().createSender(address);
      }

      // TODO durable?

      // TODO Capabilities x2

      AmqpSenderImpl.create(sender, this, completionHandler);
    });
    return this;
  }

  @Override
  public AmqpConnection createAnonymousSender(Handler<AsyncResult<AmqpSender>> completionHandler) {
    Objects.requireNonNull(completionHandler, "The completion handler must be set");
    runWithTrampoline(x -> {
      ProtonSender sender = connection.get().createSender(null);
      AmqpSenderImpl.create(sender, this, completionHandler);
    });
    return this;
  }

  @Override
  public AmqpConnection closeHandler(Handler<AmqpConnection> remoteCloseHandler) {
    this.connection.get().closeHandler(pc -> {
      if (remoteCloseHandler != null) {
        runWithTrampoline(x -> remoteCloseHandler.handle(this));
      }
    });
    return this;
  }



  ProtonConnection unwrap() {
    return this.connection.get();
  }

  public AmqpClientOptions options() {
    return options;
  }

  void register(AmqpSenderImpl sender) {
    senders.add(sender);
  }

  void register(AmqpReceiverImpl receiver) {
    receivers.add(receiver);
  }
}
