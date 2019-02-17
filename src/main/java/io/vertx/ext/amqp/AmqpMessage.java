package io.vertx.ext.amqp;

import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.qpid.proton.message.Message;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@VertxGen
public interface AmqpMessage {

  @GenIgnore
  static AmqpMessageBuilder create() {
    return new AmqpMessageBuilder();
  }

  @GenIgnore
  static AmqpMessageBuilder create(AmqpMessage existing) {
    return new AmqpMessageBuilder(existing);
  }

  @GenIgnore
  static AmqpMessageBuilder create(Message existing) {
    return new AmqpMessageBuilder(existing);
  }

  boolean isDurable();

  boolean isFirstAcquirer();

  int priority();

  String id();

  String address();

  String replyTo();

  String correlationId();

  boolean isBodyNull();

  boolean getBodyAsBoolean();

  byte getBodyAsByte();

  short getBodyAsShort();

  int getBodyAsInteger();

  long getBodyAsLong();

  float getBodyAsFloat();

  double getBodyAsDouble();

  char getBodyAsChar();

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Instant getBodyAsTimestamp();

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  UUID getBodyAsUUID();

  Buffer getBodyAsBinary();

  String getBodyAsString();

  String getBodyAsSymbol();

  <T> List<T> getBodyAsList();

  @GenIgnore
  <K, V> Map<K, V> getBodyAsMap();

  JsonObject getBodyAsJsonObject();

  JsonArray getBodyAsJsonArray();

  String subject();

  String contentType();

  String contentEncoding();

  long expiryTime();

  long creationTime();

  long ttl();

  long deliveryCount();

  String groupId();

  String replyToGroupId();

  long groupSequence();

  JsonObject applicationProperties();

  @GenIgnore
  Message unwrap();

  JsonObject getApplicationProperties();

  /**
   * Allows replying to an incoming message.
   * This method is only available is: 1) reply is enabled, 2) the message has been received. Otherwise a
   * {@link IllegalStateException} is thrown.
   *
   * @param message the message
   * @return the current message.
   */
  @Fluent
  AmqpMessage reply(AmqpMessage message);

  /**
   * Allows replying to an incoming message and expecting another reply.
   * This method is only available is: 1) reply is enabled, 2) the message has been received. Otherwise a
   * {@link IllegalStateException} is thrown.
   *
   * @param message the message
   * @param replyToReplyHandler a handler receiving the reply to this reply, must not be {@code null}
   * @return the current message.
   */
  @Fluent
  AmqpMessage reply(AmqpMessage message, Handler<AsyncResult<AmqpMessage>> replyToReplyHandler);

  //TODO What type should we use for delivery annotations and message annotations

  // TODO Add header/ footer


}
